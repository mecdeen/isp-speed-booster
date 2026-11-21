package com.ispbooster.pinger

import android.app.Activity
import android.app.AlertDialog
import android.os.Build

/**
 * "Memory lock" / lock-in-Recents guidance.
 *
 * Android has no public API to read whether an app is locked in Recents (it is an OEM launcher feature),
 * so we combine a guided setup ("I've locked it") with detection: if the booster should be running but its
 * process was killed, the lock is evidently missing and the guide comes back.
 */
object RecentsLock {

    private fun brand() = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()

    /** Lock steps for skins whose "Clear all" kills apps; null on stock-like Android, where it isn't needed. */
    private fun oemStepsRes(): Int? {
        val m = brand()
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in m } -> R.string.lock_steps_xiaomi
            "samsung" in m -> R.string.lock_steps_samsung
            listOf("oppo", "realme", "oneplus").any { it in m } -> R.string.lock_steps_oppo
            listOf("vivo", "iqoo").any { it in m } -> R.string.lock_steps_vivo
            listOf("huawei", "honor").any { it in m } -> R.string.lock_steps_huawei
            listOf("tecno", "infinix", "itel", "transsion").any { it in m } -> R.string.lock_steps_transsion
            else -> null
        }
    }

    /** True when this phone is known to need the lock, or the booster has already been killed once. */
    fun isRelevant(prefs: Prefs) = oemStepsRes() != null || prefs.killedUnexpectedly

    /** Show the reminder card: relevant here and not yet confirmed by the user. */
    fun shouldRemind(prefs: Prefs) = isRelevant(prefs) && !prefs.lockConfirmed

    fun steps(activity: Activity): String = activity.getString(oemStepsRes() ?: R.string.lock_steps_generic)

    fun showGuide(activity: Activity, prefs: Prefs, onConfirmed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.lock_guide_title)
            .setMessage(steps(activity) + "\n\n" + activity.getString(R.string.lock_guide_footer))
            .setPositiveButton(R.string.lock_confirm) { _, _ ->
                prefs.confirmLock()
                onConfirmed()
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }
}
