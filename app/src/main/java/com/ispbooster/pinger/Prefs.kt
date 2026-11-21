package com.ispbooster.pinger

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

enum class Mode { HTTP, ICMP }

/** User-facing presets. The technical values stay hidden unless the user opens Advanced. */
enum class Profile(
    val titleRes: Int,
    val descRes: Int,
    val mode: Mode,
    val target: String,
    val intervalMs: Long,
) {
    GAMING(R.string.profile_gaming, R.string.profile_gaming_desc, Mode.ICMP, Prefs.HUTCH_PING_HOST, 500),
    AGGRESSIVE(R.string.profile_aggressive, R.string.profile_aggressive_desc, Mode.HTTP, Prefs.HUTCH_URL, 10_000),
    BALANCED(R.string.profile_balanced, R.string.profile_balanced_desc, Mode.HTTP, Prefs.HUTCH_URL, Prefs.HUTCH_INTERVAL_MS),
    RELAXED(R.string.profile_relaxed, R.string.profile_relaxed_desc, Mode.HTTP, Prefs.HUTCH_URL, 60_000),
    CUSTOM(R.string.profile_custom, R.string.profile_custom_desc, Mode.HTTP, Prefs.HUTCH_URL, Prefs.HUTCH_INTERVAL_MS),
}

/** Settings in plain SharedPreferences (in-memory reads; the service re-reads them every tick, so changes apply live). */
class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val mode: Mode
        get() = Mode.entries.firstOrNull { it.name == sp.getString(KEY_MODE, null) } ?: Mode.HTTP

    val target: String
        get() {
            val t = sp.getString(KEY_TARGET, null)?.takeIf { it.isNotBlank() } ?: HUTCH_URL
            // Older builds saved the Hutch web host as the ICMP target; it never answers pings.
            return if (mode == Mode.ICMP && t.equals(hostOf(HUTCH_URL), true)) ICMP_HOSTS[0].second else t
        }

    val intervalMs: Long
        get() = sp.getLong(KEY_INTERVAL, HUTCH_INTERVAL_MS).coerceIn(minIntervalMs(mode), MAX_INTERVAL_MS)

    /**
     * True while the user wants the booster on. Lets the service come back after an OEM kills it (Recents swipe),
     * but never after the user pressed Stop. Written with commit() because the process may be killed right after.
     */
    var shouldRun: Boolean
        get() = sp.getBoolean(KEY_SHOULD_RUN, false)
        set(value) { sp.edit().putBoolean(KEY_SHOULD_RUN, value).commit() }

    /** User said they locked the app in Recents. Cleared again if we detect we were killed anyway. */
    val lockConfirmed: Boolean
        get() = sp.getBoolean(KEY_LOCK_CONFIRMED, false)

    /** The booster was killed while it should have been running (e.g. Recents cleared without a lock). */
    val killedUnexpectedly: Boolean
        get() = sp.getBoolean(KEY_KILLED, false)

    fun confirmLock() {
        sp.edit().putBoolean(KEY_LOCK_CONFIRMED, true).putBoolean(KEY_KILLED, false).apply()
    }

    fun markKilledUnexpectedly() {
        sp.edit().putBoolean(KEY_KILLED, true).putBoolean(KEY_LOCK_CONFIRMED, false).apply()
    }

    val profile: Profile
        get() = Profile.entries.firstOrNull { it.name == sp.getString(KEY_PROFILE, null) }
            // Fresh install: Balanced. Upgrade from a build without profiles: keep the user's values as Custom.
            ?: if (sp.contains(KEY_INTERVAL)) Profile.CUSTOM else Profile.BALANCED

    /** Selects a preset: writes its values in one commit. CUSTOM keeps the current advanced values. */
    fun applyProfile(p: Profile) {
        val edit = sp.edit().putString(KEY_PROFILE, p.name)
        if (p != Profile.CUSTOM) {
            edit.putString(KEY_MODE, p.mode.name).putString(KEY_TARGET, p.target).putLong(KEY_INTERVAL, p.intervalMs)
        }
        edit.apply()
    }

    /** Advanced edit: writes mode, target and interval in one commit and switches the profile to CUSTOM. */
    fun save(mode: Mode, target: String, intervalMs: Long) {
        sp.edit()
            .putString(KEY_PROFILE, Profile.CUSTOM.name)
            .putString(KEY_MODE, mode.name)
            .putString(KEY_TARGET, target.trim())
            .putLong(KEY_INTERVAL, intervalMs.coerceIn(minIntervalMs(mode), MAX_INTERVAL_MS))
            .apply()
    }

    fun resetToHutch() {
        applyProfile(Profile.BALANCED)
    }

    companion object {
        /** RunHutch endpoint; interval default is 20 s (RunHutch itself uses 30 s). */
        const val HUTCH_URL = "https://selfcare.hutch.lk/selfcare/login.html"
        const val HUTCH_INTERVAL_MS = 20_000L

        /**
         * Special ICMP target: ping the DNS servers the (Hutch) mobile connection hands out, read at runtime.
         * Stays valid even if fixed IPs change.
         */
        const val CARRIER_DNS = "carrier-dns"

        /**
         * ICMP quick picks (label to host). The Hutch IPs are Hutch-operated Ookla speed-test servers
         * (AS132447 / AS17470) that answer ping. Web servers such as selfcare.hutch.lk drop pings.
         */
        val ICMP_HOSTS = listOf(
            "Hutch 1" to HUTCH_PING_HOST,
            "Hutch 2" to "43.252.14.74",
            "Hutch DNS (auto)" to CARRIER_DNS,
            "Google" to "8.8.8.8",
            "Cloudflare" to "1.1.1.1",
        )

        private val IP_REGEX = Regex("""^[0-9.]+$|^[0-9a-fA-F:]+:[0-9a-fA-F:.]*$""")
        const val MAX_INTERVAL_MS = 3_600_000L

        private const val KEY_MODE = "mode"
        private const val KEY_TARGET = "target"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_SHOULD_RUN = "should_run"
        private const val KEY_PROFILE = "profile"
        private const val KEY_LOCK_CONFIRMED = "lock_confirmed"
        private const val KEY_KILLED = "killed_unexpectedly"
        const val HUTCH_PING_HOST = "103.21.167.134"

        fun minIntervalMs(mode: Mode) = if (mode == Mode.HTTP) 1_000L else 100L
        fun timeoutMs(mode: Mode) = if (mode == Mode.HTTP) 10_000 else 1_500

        /** Converts a target when the user switches mode (URL <-> bare host). */
        fun convertTarget(target: String, to: Mode): String {
            val t = target.trim()
            return when (to) {
                // The Hutch web portal does not answer pings, so switching to ICMP picks a host that does.
                Mode.ICMP -> if (t.isEmpty() || t.equals(HUTCH_URL, true)) ICMP_HOSTS[0].second else hostOf(t)
                Mode.HTTP -> when {
                    t.contains("://") -> t
                    t.isEmpty() || t == CARRIER_DNS || IP_REGEX.matches(t) -> HUTCH_URL // bare IPs rarely serve HTTPS
                    else -> "https://$t/"
                }
            }
        }

        fun hostOf(target: String): String =
            if (target.contains("://")) Uri.parse(target).host.orEmpty() else target.trim().substringBefore('/')

        fun normalizeUrl(target: String): String {
            val t = target.trim()
            return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
        }
    }
}
