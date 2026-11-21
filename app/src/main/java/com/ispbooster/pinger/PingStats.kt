package com.ispbooster.pinger

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class Snapshot(
    val running: Boolean,
    val status: Status,
    val statusDetail: String,
    val sent: Long,
    val received: Long,
    val lastMs: Double,
    val avgMs: Double,
    val jitterMs: Double,
    val lossPct: Double,
    /** 0..100, or -1 when there is no data yet. */
    val score: Int,
    val uptimeMs: Long,
    /** ms until the next request, or -1 when not scheduled. */
    val nextInMs: Long,
)

enum class Status { STOPPED, STARTING, OK, FAILING }

/**
 * In-memory stats shared between the service (writer) and the activity (reader, polls ~2x/sec while visible).
 * Lives only as long as the process — killing the app from the tile wipes it, by design.
 */
object PingStats {
    private const val WINDOW = 50

    @Volatile
    var running = false
        private set

    private var mode = Mode.HTTP
    private var status = Status.STOPPED
    private var detail = ""
    private var sent = 0L
    private var received = 0L
    private var lastMs = -1.0
    private var startedAt = 0L
    private var lastProbeAt = 0L

    /** Ring buffer of recent results: rtt in ms, or -1 for a failure. */
    private val window = DoubleArray(WINDOW)
    private var count = 0
    private var head = 0

    @Synchronized
    fun begin(mode: Mode) {
        this.mode = mode
        running = true
        status = Status.STARTING
        detail = ""
        sent = 0; received = 0; lastMs = -1.0
        startedAt = SystemClock.elapsedRealtime()
        lastProbeAt = 0
        count = 0; head = 0
    }

    @Synchronized
    fun end() {
        running = false
        status = Status.STOPPED
        lastProbeAt = 0
    }

    /** Target or mode changed while running: old samples are not comparable, start the score fresh. */
    @Synchronized
    fun resetWindow(mode: Mode) {
        this.mode = mode
        count = 0; head = 0; lastMs = -1.0
    }

    @Synchronized
    fun record(result: ProbeResult, networkLabel: String, probeStartedAt: Long) {
        if (!running) return
        sent++
        if (result.ok) received++
        lastMs = if (result.ok) result.rttMs else -1.0
        status = if (result.ok) Status.OK else Status.FAILING
        detail = if (result.ok) "$networkLabel · ${result.detail}" else result.detail
        lastProbeAt = probeStartedAt
        window[head] = if (result.ok) result.rttMs else -1.0
        head = (head + 1) % WINDOW
        if (count < WINDOW) count++
    }

    @Synchronized
    fun snapshot(intervalMs: Long): Snapshot {
        val oldestFirst = (0 until count).map { window[(head - count + it + WINDOW) % WINDOW] }
        val rtts = oldestFirst.filter { it >= 0 }
        val fails = count - rtts.size
        val avg = if (rtts.isEmpty()) -1.0 else rtts.average()
        val jitter = if (rtts.size < 2) -1.0 else rtts.zipWithNext { a, b -> abs(a - b) }.average()
        val loss = if (count == 0) -1.0 else fails * 100.0 / count
        val now = SystemClock.elapsedRealtime()
        return Snapshot(
            running = running,
            status = status,
            statusDetail = detail,
            sent = sent,
            received = received,
            lastMs = lastMs,
            avgMs = avg,
            jitterMs = jitter,
            lossPct = loss,
            score = score(mode, rtts, fails),
            uptimeMs = if (running) now - startedAt else 0,
            nextInMs = if (running && lastProbeAt > 0) maxOf(0, lastProbeAt + intervalMs - now) else -1,
        )
    }

    /**
     * Connection score 0..100 over the last [WINDOW] results.
     * Penalties: latency up to 40, jitter up to 25, packet loss up to 35 (3 points per 1% lost).
     * HTTP latency targets are looser than ICMP because each request includes TCP/TLS overhead.
     */
    fun score(mode: Mode, rtts: List<Double>, fails: Int): Int {
        if (rtts.isEmpty()) return if (fails == 0) -1 else 0
        val good = if (mode == Mode.HTTP) 300.0 else 50.0
        val bad = if (mode == Mode.HTTP) 2000.0 else 300.0
        val avg = rtts.average()
        val jitter = if (rtts.size < 2) 0.0 else rtts.zipWithNext { a, b -> abs(a - b) }.average()
        val loss = fails * 100.0 / (rtts.size + fails)
        val penalty = 40 * ((avg - good) / (bad - good)).coerceIn(0.0, 1.0) +
            25 * (jitter / (bad / 3)).coerceIn(0.0, 1.0) +
            min(35.0, loss * 3)
        return (100 - penalty).roundToInt().coerceIn(0, 100)
    }
}
