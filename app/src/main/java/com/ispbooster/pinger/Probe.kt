package com.ispbooster.pinger

import android.net.ConnectivityManager
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ProbeResult(val ok: Boolean, val rttMs: Double, val detail: String)

/**
 * One probe = one request over the phone's current connection (Hutch SIM data, or Wi-Fi from a Hutch router).
 * Implementations are used from a single worker thread only.
 */
interface Probe {
    fun run(): ProbeResult
    fun close() {}
}

private fun msSince(startNanos: Long) = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

private fun describe(e: Throwable): String = when (e) {
    is SocketTimeoutException -> "Timed out"
    is UnknownHostException -> "DNS lookup failed"
    is ConnectException -> "Connection refused"
    is SSLException -> "TLS error"
    is ErrnoException -> e.message ?: "Network error"
    else -> e.javaClass.simpleName
}

/** RunHutch method: a plain HTTP GET. Any HTTP response (even 4xx/5xx) proves the link is alive. */
class HttpProbe(private val url: String, private val timeoutMs: Int) : Probe {
    private val buffer = ByteArray(8 * 1024)

    override fun run(): ProbeResult {
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            val rtt = msSince(start) // time to response headers
            // Read the body like a browser would, then close the stream so the socket returns to the keep-alive pool.
            (if (code >= 400) conn.errorStream else conn.inputStream)?.use { s ->
                while (s.read(buffer) != -1) { /* drain */ }
            }
            ProbeResult(true, rtt, "HTTP $code")
        } catch (e: Exception) {
            ProbeResult(false, -1.0, describe(e))
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
    }
}

/**
 * Z Pinger style ping. Tries these methods in order and sticks with the first one that works on this device:
 *  1. Unprivileged ICMP ping socket (no root, no extra process).
 *  2. The system `ping` binary.
 *  3. TCP connect time to port 443/53, for ROMs that block ICMP for apps entirely.
 */
class IcmpProbe(
    private val host: String,
    private val timeoutMs: Int,
    private val cm: ConnectivityManager,
) : Probe {
    private enum class Method { SOCKET, EXEC, TCP }

    private var method = Method.SOCKET
    private var methodWorked = false // once a method got a reply, plain timeouts never demote it
    private var failsInARow = 0

    private var fd: FileDescriptor? = null
    private var address: InetAddress? = null
    private var seq = 0
    private val recvBuf = ByteArray(2048)

    override fun run(): ProbeResult {
        if (host.isEmpty()) return ProbeResult(false, -1.0, "No host set")
        val result = when (method) {
            Method.SOCKET -> socketPing()
            Method.EXEC -> execPing()
            Method.TCP -> tcpPing()
        } ?: return demoteAndRetry() // null = method not supported on this device

        if (result.ok) {
            methodWorked = true
            failsInARow = 0
        } else if (!methodWorked && ++failsInARow >= 3 && method != Method.TCP) {
            demote() // never got a single reply with this method: try the next one from the next probe
        }
        return result
    }

    private fun demote() {
        close()
        method = if (method == Method.SOCKET) Method.EXEC else Method.TCP
        methodWorked = false
        failsInARow = 0
    }

    private fun demoteAndRetry(): ProbeResult {
        if (method == Method.TCP) return ProbeResult(false, -1.0, "Ping not supported")
        demote()
        return run()
    }

    private fun resolve(): InetAddress =
        address ?: (if (host == Prefs.CARRIER_DNS) carrierDns() else InetAddress.getByName(host)).also { address = it }

    /** First DNS server (IPv4 preferred) handed out by the current connection. */
    private fun carrierDns(): InetAddress {
        val servers = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
        return servers.firstOrNull { it !is Inet6Address } ?: servers.firstOrNull()
            ?: throw UnknownHostException("No carrier DNS")
    }

    // ---- 1. ICMP ping socket ----

    private fun socketPing(): ProbeResult? = try {
        val addr = resolve()
        if (fd == null) openSocket(addr)
        echo(addr)
    } catch (e: ErrnoException) {
        close()
        if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM ||
            e.errno == OsConstants.EPROTONOSUPPORT || e.errno == OsConstants.EAFNOSUPPORT ||
            e.errno == OsConstants.EINVAL
        ) {
            null // ping sockets blocked on this device
        } else {
            address = null
            ProbeResult(false, -1.0, describe(e))
        }
    } catch (e: Exception) {
        close(); address = null // re-resolve next time (network may have changed)
        ProbeResult(false, -1.0, describe(e))
    }

    private fun openSocket(addr: InetAddress) {
        close()
        val v6 = addr is Inet6Address
        fd = Os.socket(
            if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET,
            OsConstants.SOCK_DGRAM,
            if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
        )
    }

    private fun echo(addr: InetAddress): ProbeResult {
        val f = fd!!
        val v6 = addr is Inet6Address
        seq = (seq + 1) and 0xFFFF
        // ICMP header: type, code, checksum(2), id(2), seq(2) + payload. The kernel sets id and checksum.
        val packet = ByteArray(8 + PAYLOAD)
        packet[0] = (if (v6) 128 else 8).toByte()
        packet[6] = (seq shr 8).toByte()
        packet[7] = seq.toByte()
        for (i in 0 until PAYLOAD) packet[8 + i] = i.toByte()

        val start = SystemClock.elapsedRealtimeNanos()
        Os.sendto(f, packet, 0, packet.size, 0, addr, 0)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val poll = StructPollfd().apply { fd = f; events = OsConstants.POLLIN.toShort() }
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return ProbeResult(false, -1.0, "Timed out")
            poll.revents = 0
            if (Os.poll(arrayOf(poll), remaining.toInt()) <= 0) continue
            val n = Os.read(f, recvBuf, 0, recvBuf.size)
            if (n < 8) continue
            val type = recvBuf[0].toInt() and 0xFF
            val rseq = ((recvBuf[6].toInt() and 0xFF) shl 8) or (recvBuf[7].toInt() and 0xFF)
            if (type == (if (v6) 129 else 0) && rseq == seq) {
                return ProbeResult(true, msSince(start), "ICMP reply")
            }
            // a late reply to an earlier probe: ignore and keep waiting
        }
    }

    // ---- 2. system ping binary ----

    private fun execPing(): ProbeResult? {
        val target = if (host == Prefs.CARRIER_DNS) {
            try { resolve().hostAddress } catch (e: Exception) { return ProbeResult(false, -1.0, describe(e)) }
        } else host
        val out = try {
            val process = ProcessBuilder(
                "/system/bin/ping", "-n", "-c", "1", "-W", "${maxOf(1, (timeoutMs + 999) / 1000)}", target,
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        } catch (_: IOException) {
            return null
        }
        TIME_REGEX.find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            return ProbeResult(true, it, "ping reply")
        }
        val lower = out.lowercase()
        return when {
            "permission denied" in lower || "not permitted" in lower || "socket" in lower -> null
            "unknown host" in lower || "bad address" in lower -> { address = null; ProbeResult(false, -1.0, "DNS lookup failed") }
            else -> ProbeResult(false, -1.0, "Timed out")
        }
    }

    // ---- 3. TCP connect "ping" ----

    private fun tcpPing(): ProbeResult = try {
        val addr = resolve()
        var result = ProbeResult(false, -1.0, "Timed out")
        for (port in TCP_PORTS) {
            val socket = Socket()
            val start = SystemClock.elapsedRealtimeNanos()
            try {
                socket.connect(InetSocketAddress(addr, port), timeoutMs)
                result = ProbeResult(true, msSince(start), "TCP :$port")
                break
            } catch (e: ConnectException) {
                // A refusal (RST) still proves the host answered.
                if (e.message?.contains("ECONNREFUSED") == true) {
                    result = ProbeResult(true, msSince(start), "TCP :$port")
                    break
                }
                result = ProbeResult(false, -1.0, describe(e))
            } catch (e: IOException) {
                result = ProbeResult(false, -1.0, describe(e))
            } finally {
                runCatching { socket.close() }
            }
        }
        if (!result.ok) address = null
        result
    } catch (e: Exception) {
        address = null
        ProbeResult(false, -1.0, describe(e))
    }

    override fun close() {
        fd?.let { runCatching { Os.close(it) } }
        fd = null
    }

    private companion object {
        const val PAYLOAD = 56
        val TCP_PORTS = intArrayOf(443, 53)
        val TIME_REGEX = Regex("time[=<]([0-9.]+)")
    }
}
