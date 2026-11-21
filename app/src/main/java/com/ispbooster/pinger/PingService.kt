package com.ispbooster.pinger

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/**
 * Foreground service that sends one probe per interval on a single worker thread.
 * The notification is intentionally static: status and score are shown only inside the app.
 */
class PingService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var cm: ConnectivityManager
    private val lock = Object()

    @Volatile private var stopped = true
    /** Bumped on every start so a worker left over from a previous run (e.g. stuck in a timeout) exits quietly. */
    @Volatile private var generation = 0
    private var wakeLock: PowerManager.WakeLock? = null

    // Interval/target edits in the app wake the worker so they take effect immediately.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> wake() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        cm = getSystemService(ConnectivityManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> { shutdown(exitApp = true); return START_NOT_STICKY }
            ACTION_START -> begin()
            // RESTART (alarm after a Recents swipe) or null (system re-created us via START_STICKY):
            // resume only if the user had it running and never pressed Stop.
            else -> {
                if (Build.VERSION.SDK_INT >= 26 && intent?.action == ACTION_RESTART) goForeground() // required for startForegroundService
                if (prefs.shouldRun) {
                    // A fresh service (stopped) that should be running means our process was killed:
                    // most likely Recents was cleared without the app being locked.
                    if (stopped) prefs.markKilledUnexpectedly()
                    begin()
                } else {
                    shutdown(exitApp = false)
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    /**
     * The user swiped the app away in Recents. Stock Android keeps this foreground service alive, but some OEM
     * skins kill the whole process. Schedule a quick restart; if we survived, it's a harmless no-op.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (stopped || !prefs.shouldRun) return
        val intent = Intent(this, PingService::class.java).setAction(ACTION_RESTART)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 2, intent, flags)
        else PendingIntent.getService(this, 2, intent, flags)
        runCatching {
            getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1_500, pending)
        }
    }

    override fun onDestroy() {
        teardown()
        instance = null
        super.onDestroy()
    }

    private fun begin() {
        goForeground() // must happen first on every startForegroundService() call
        if (!stopped) return
        stopped = false
        prefs.shouldRun = true
        PingStats.begin(prefs.mode)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedBooster:ping")
            .apply { setReferenceCounted(false); acquire() }
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        val gen = ++generation
        thread(name = "pinger", isDaemon = true) { loop(gen) }
        refreshTile(this)
    }

    private fun alive(gen: Int) = !stopped && gen == generation

    private fun loop(gen: Int) {
        var probe: Probe? = null
        var probeKey = ""
        try {
            while (alive(gen)) {
                val mode = prefs.mode
                val target = prefs.target

                val key = "$mode|$target"
                if (key != probeKey) {
                    probe?.close()
                    probe = when (mode) {
                        Mode.HTTP -> HttpProbe(Prefs.normalizeUrl(target), Prefs.timeoutMs(mode))
                        Mode.ICMP -> IcmpProbe(Prefs.hostOf(target), Prefs.timeoutMs(mode), cm)
                    }
                    if (probeKey.isNotEmpty()) PingStats.resetWindow(mode)
                    probeKey = key
                }

                val startedAt = SystemClock.elapsedRealtime()
                val result = probe!!.run()
                if (!alive(gen)) break
                PingStats.record(result, networkLabel(), startedAt)

                // Re-reads the interval on every wake-up, so a changed interval applies to the current wait.
                waitUntil(gen) { startedAt + prefs.intervalMs }
            }
        } finally {
            probe?.close()
        }
    }

    /** Sleeps until the time produced by [deadline] (re-evaluated on every wake-up) unless stopped. */
    private fun waitUntil(gen: Int, deadline: () -> Long) {
        synchronized(lock) {
            while (alive(gen)) {
                val remaining = deadline() - SystemClock.elapsedRealtime()
                if (remaining <= 0) return
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun networkLabel(): String {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Online"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online"
        }
    }

    private fun teardown() {
        if (stopped) return
        stopped = true
        wake()
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        PingStats.end()
    }

    /** Stops everything. With [exitApp], also closes all app screens and kills the process. */
    fun shutdown(exitApp: Boolean) {
        prefs.shouldRun = false // user stop: nothing may restart us
        teardown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        refreshTile(this)
        if (exitApp) killApp(this)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val exit = PendingIntent.getService(this, 1, Intent(this, PingService::class.java).setAction(ACTION_EXIT), flags)

        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        builder
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.stop), exit,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "booster"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.ispbooster.pinger.START"
        private const val ACTION_EXIT = "com.ispbooster.pinger.EXIT"
        private const val ACTION_RESTART = "com.ispbooster.pinger.RESTART"

        @Volatile
        private var instance: PingService? = null

        /** May throw ForegroundServiceStartNotAllowedException when called from the background (Android 12+). */
        fun start(context: Context) {
            val intent = Intent(context, PingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        val isAlive: Boolean get() = instance != null

        /** In-app stop: stops the service but keeps the app open. */
        fun stop() {
            instance?.shutdown(exitApp = false)
        }

        /** Tile / notification stop: stops the service and removes the whole app from memory. */
        fun exit(context: Context) {
            instance?.shutdown(exitApp = true) ?: killApp(context)
        }

        fun refreshTile(context: Context) {
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, PingTileService::class.java))
            }
        }

        private fun killApp(context: Context) {
            // Close every app screen and drop it from Recents, then end the process.
            context.getSystemService(ActivityManager::class.java).appTasks.forEach {
                runCatching { it.finishAndRemoveTask() }
            }
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        }
    }
}
