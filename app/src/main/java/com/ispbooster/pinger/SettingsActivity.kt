package com.ispbooster.pinger

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Settings page. Everyday users pick a named Boost mode; the technical knobs sit in a collapsed Advanced section.
 * Every change is saved immediately and the running service picks it up live.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var profileList: LinearLayout
    private lateinit var advancedToggle: TextView
    private lateinit var advancedSection: View
    private lateinit var modeGroup: RadioGroup
    private lateinit var targetLabel: TextView
    private lateinit var targetInput: EditText
    private lateinit var hostChipScroll: View
    private lateinit var hostChipRow: LinearLayout
    private lateinit var intervalInput: EditText
    private lateinit var intervalHint: TextView
    private lateinit var batteryState: TextView

    private val profileRows = mutableMapOf<Profile, View>()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        profileList = findViewById(R.id.profileList)
        advancedToggle = findViewById(R.id.advancedToggle)
        advancedSection = findViewById(R.id.advancedSection)
        modeGroup = findViewById(R.id.modeGroup)
        targetLabel = findViewById(R.id.targetLabel)
        targetInput = findViewById(R.id.targetInput)
        hostChipScroll = findViewById(R.id.hostChipScroll)
        hostChipRow = findViewById(R.id.hostChipRow)
        intervalInput = findViewById(R.id.intervalInput)
        intervalHint = findViewById(R.id.intervalHint)
        batteryState = findViewById(R.id.batteryState)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.batteryRow).setOnClickListener { BatteryHelper.requestIgnore(this) }
        findViewById<View>(R.id.lockRow).setOnClickListener { RecentsLock.showGuide(this, prefs) { renderLockState() } }
        advancedToggle.setOnClickListener { setAdvancedVisible(advancedSection.visibility != View.VISIBLE) }
        findViewById<Button>(R.id.applyButton).setOnClickListener { if (applyAdvanced()) toast(R.string.saved) }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            prefs.resetToHutch()
            load()
            hideKeyboard()
            toast(R.string.reset_done)
        }

        buildProfileRows()
        buildHostChips()

        modeGroup.setOnCheckedChangeListener { _, _ ->
            if (loading) return@setOnCheckedChangeListener
            val mode = selectedMode()
            targetInput.setText(Prefs.convertTarget(targetInput.text.toString(), mode))
            val current = intervalInput.text.toString().toDoubleOrNull()
            if (current == null || current * 1000 < Prefs.minIntervalMs(mode)) {
                intervalInput.setText(formatSeconds(Prefs.minIntervalMs(mode)))
            }
            updateModeViews(mode)
            applyAdvanced()
        }

        val done = TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { if (applyAdvanced()) toast(R.string.saved); true } else false
        }
        targetInput.setOnEditorActionListener(done)
        intervalInput.setOnEditorActionListener(done)

        load()
        setAdvancedVisible(prefs.profile == Profile.CUSTOM)
    }

    override fun onResume() {
        super.onResume()
        batteryState.setText(
            if (BatteryHelper.isIgnoringOptimizations(this)) R.string.battery_state_off else R.string.battery_state_on,
        )
        renderLockState()
    }

    private fun renderLockState() {
        findViewById<TextView>(R.id.lockState).setText(
            when {
                prefs.lockConfirmed -> R.string.lock_state_on
                RecentsLock.isRelevant(prefs) -> R.string.lock_state_off
                else -> R.string.lock_state_na
            },
        )
    }

    override fun onPause() {
        if (isFinishing) applyAdvanced(quiet = true) // leaving the page keeps valid, unsaved advanced edits
        super.onPause()
    }

    // ---- Boost mode ----

    private fun buildProfileRows() {
        val dp = resources.displayMetrics.density
        for (p in Profile.entries) {
            val title = TextView(this).apply {
                setText(p.titleRes)
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            val desc = TextView(this).apply {
                setText(p.descRes)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(desc)
            }
            val radio = RadioButton(this).apply {
                isClickable = false
                isFocusable = false
                tag = RADIO_TAG
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_option)
                val pad = (12 * dp).roundToInt()
                setPadding(pad, pad, pad, pad)
                addView(radio)
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * dp).roundToInt() }
                isClickable = true
                setOnClickListener { onProfileSelected(p) }
            }
            profileRows[p] = row
            profileList.addView(row)
        }
    }

    private fun onProfileSelected(p: Profile) {
        hideKeyboard()
        prefs.applyProfile(p)
        load()
        if (p == Profile.CUSTOM) {
            setAdvancedVisible(true)
            findViewById<ScrollView>(R.id.scroll).post {
                findViewById<ScrollView>(R.id.scroll).smoothScrollTo(0, advancedToggle.top)
            }
        }
    }

    private fun renderProfiles() {
        val current = prefs.profile
        for ((p, row) in profileRows) {
            val selected = p == current
            row.isSelected = selected
            row.findViewWithTag<RadioButton>(RADIO_TAG).isChecked = selected
        }
    }

    private fun setAdvancedVisible(visible: Boolean) {
        advancedSection.visibility = if (visible) View.VISIBLE else View.GONE
        advancedToggle.setText(if (visible) R.string.advanced_hide else R.string.advanced_show)
    }

    // ---- Advanced ----

    private fun selectedMode() = if (modeGroup.checkedRadioButtonId == R.id.modeIcmp) Mode.ICMP else Mode.HTTP

    private fun load() {
        loading = true
        val mode = prefs.mode
        modeGroup.check(if (mode == Mode.ICMP) R.id.modeIcmp else R.id.modeHttp)
        targetInput.setText(prefs.target)
        intervalInput.setText(formatSeconds(prefs.intervalMs))
        updateModeViews(mode)
        renderProfiles()
        loading = false
    }

    private fun updateModeViews(mode: Mode) {
        targetLabel.setText(if (mode == Mode.HTTP) R.string.target_url else R.string.target_host)
        intervalHint.setText(if (mode == Mode.HTTP) R.string.interval_hint_http else R.string.interval_hint_icmp)
        hostChipScroll.visibility = if (mode == Mode.ICMP) View.VISIBLE else View.GONE
    }

    /**
     * Validates and saves the advanced fields. Only an actual change switches Boost mode to Custom.
     * Returns false (keeping the old settings) when the input is invalid.
     */
    private fun applyAdvanced(quiet: Boolean = false): Boolean {
        val mode = selectedMode()
        val raw = targetInput.text.toString().trim()
        val target = when {
            raw.isEmpty() -> ""
            mode == Mode.HTTP -> Prefs.normalizeUrl(raw)
            else -> Prefs.hostOf(raw)
        }
        if (target.isEmpty()) { if (!quiet) toast(R.string.invalid_target); return false }

        val seconds = intervalInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (seconds == null || seconds <= 0) { if (!quiet) toast(R.string.invalid_interval); return false }
        val ms = (seconds * 1000).roundToLong().coerceIn(Prefs.minIntervalMs(mode), Prefs.MAX_INTERVAL_MS)

        val changed = mode != prefs.mode || target != prefs.target || ms != prefs.intervalMs
        if (changed) prefs.save(mode, target, ms)
        if (!quiet) {
            targetInput.setText(target)
            intervalInput.setText(formatSeconds(ms))
            hideKeyboard()
            renderProfiles()
        }
        return true
    }

    private fun buildHostChips() {
        val dp = resources.displayMetrics.density
        for ((label, host) in Prefs.ICMP_HOSTS) {
            val chip = TextView(this, null, 0, R.style.Chip).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (8 * dp).roundToInt() }
                setOnClickListener { targetInput.setText(host); if (applyAdvanced()) toast(R.string.saved) }
            }
            hostChipRow.addView(chip)
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private companion object {
        const val RADIO_TAG = "radio"
    }
}
