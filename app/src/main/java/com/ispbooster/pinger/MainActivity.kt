package com.ispbooster.pinger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Home screen: live status + score (shown only here) and start/stop. Settings live in [SettingsActivity]. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    private lateinit var scoreRing: ScoreRingView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var nextText: TextView
    private lateinit var statLast: TextView
    private lateinit var statAvg: TextView
    private lateinit var statJitter: TextView
    private lateinit var statLoss: TextView
    private lateinit var statSent: TextView
    private lateinit var statUptime: TextView
    private lateinit var toggleButton: Button
    private lateinit var batteryBanner: View
    private lateinit var configSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        scoreRing = findViewById(R.id.scoreRing)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        nextText = findViewById(R.id.nextText)
        statLast = findViewById(R.id.statLast)
        statAvg = findViewById(R.id.statAvg)
        statJitter = findViewById(R.id.statJitter)
        statLoss = findViewById(R.id.statLoss)
        statSent = findViewById(R.id.statSent)
        statUptime = findViewById(R.id.statUptime)
        toggleButton = findViewById(R.id.toggleButton)
        batteryBanner = findViewById(R.id.batteryBanner)
        configSummary = findViewById(R.id.configSummary)

        findViewById<TextView>(R.id.batteryText).text = BatteryHelper.message(this)
        findViewById<Button>(R.id.batteryButton).setOnClickListener { BatteryHelper.requestIgnore(this) }
        toggleButton.setOnClickListener { onToggle() }

        val openSettings = View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.settingsButton).setOnClickListener(openSettings)
        configSummary.setOnClickListener(openSettings)

        findViewById<View>(R.id.developerCredit).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_URL))) }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryBanner.visibility = if (BatteryHelper.isIgnoringOptimizations(this)) View.GONE else View.VISIBLE
        configSummary.text = getString(R.string.config_summary_profile, getString(prefs.profile.titleRes))
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker) // nothing redraws while the app is in the background
        super.onPause()
    }

    private fun onToggle() {
        if (PingStats.running) {
            PingService.stop()
            render()
            return
        }
        if (BatteryHelper.isIgnoringOptimizations(this)) {
            startBooster()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.battery_dialog_title)
                .setMessage(BatteryHelper.message(this))
                .setPositiveButton(R.string.battery_open) { _, _ -> BatteryHelper.requestIgnore(this) }
                .setNegativeButton(R.string.battery_anyway) { _, _ -> startBooster() }
                .show()
        }
    }

    private fun startBooster() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Not required to run; without it Android just hides the (static) service notification.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        PingService.start(this)
        render()
    }

    private fun render() {
        val s = PingStats.snapshot(prefs.intervalMs)

        val (scoreColor, label) = when {
            s.score < 0 -> color(R.color.idle) to getString(R.string.label_no_data)
            s.score >= 85 -> color(R.color.good) to getString(R.string.label_excellent)
            s.score >= 65 -> color(R.color.good) to getString(R.string.label_good)
            s.score >= 40 -> color(R.color.warn) to getString(R.string.label_fair)
            else -> color(R.color.bad) to getString(R.string.label_poor)
        }
        scoreRing.set(s.score, label, if (s.running) scoreColor else color(R.color.idle))

        val (dotColor, status) = when (s.status) {
            Status.STOPPED -> color(R.color.idle) to getString(R.string.status_stopped)
            Status.STARTING -> color(R.color.warn) to getString(R.string.status_starting)
            Status.OK -> color(R.color.good) to s.statusDetail
            Status.FAILING -> color(R.color.bad) to s.statusDetail
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        statusText.text = status

        val interval = prefs.intervalMs
        nextText.text = when {
            !s.running -> ""
            interval < 2_000 -> getString(R.string.every, formatSeconds(interval))
            s.nextInMs >= 0 -> getString(R.string.next_in, ((s.nextInMs + 999) / 1000).toInt())
            else -> ""
        }

        statLast.text = ms(s.lastMs)
        statAvg.text = ms(s.avgMs)
        statJitter.text = ms(s.jitterMs)
        statLoss.text = if (s.lossPct < 0) DASH else String.format(Locale.US, "%.1f %%", s.lossPct)
        statSent.text = if (s.sent == 0L) DASH else String.format(Locale.US, "%d / %d", s.sent, s.received)
        statUptime.text = if (s.running) duration(s.uptimeMs) else DASH

        toggleButton.setText(if (s.running) R.string.stop else R.string.start)
        toggleButton.backgroundTintList = ColorStateList.valueOf(color(if (s.running) R.color.bad else R.color.accent))
    }

    private fun color(res: Int) = getColor(res)

    private companion object {
        const val DASH = "—"
        const val DEVELOPER_URL = "https://github.com/mecdeen"

        fun ms(v: Double) = when {
            v < 0 -> DASH
            v < 10 -> String.format(Locale.US, "%.1f ms", v)
            else -> String.format(Locale.US, "%d ms", v.roundToInt())
        }

        fun duration(ms: Long): String {
            val t = ms / 1000
            return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
        }
    }
}
