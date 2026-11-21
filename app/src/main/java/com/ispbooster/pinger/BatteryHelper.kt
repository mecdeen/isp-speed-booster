package com.ispbooster.pinger

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryHelper {

    fun isIgnoringOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Opens the system "allow to run in background" prompt, falling back to the full list. */
    @SuppressLint("BatteryLife") // sideloaded utility whose core function needs this exemption
    fun requestIgnore(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(direct)
        } catch (_: Exception) {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Extra advice for OEM skins that kill background apps beyond stock Android's rules. */
    fun oemHint(context: Context): String? {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        val res = when {
            listOf("xiaomi", "redmi", "poco").any { it in m } -> R.string.oem_xiaomi
            "samsung" in m -> R.string.oem_samsung
            listOf("huawei", "honor").any { it in m } -> R.string.oem_huawei
            listOf("oppo", "realme", "oneplus").any { it in m } -> R.string.oem_oppo
            listOf("vivo", "iqoo").any { it in m } -> R.string.oem_vivo
            else -> return null
        }
        return context.getString(res)
    }

    fun message(context: Context): String {
        val base = context.getString(R.string.battery_body)
        val oem = oemHint(context)?.let { "\n\n$it" }.orEmpty()
        return "$base$oem\n\n${context.getString(R.string.recents_lock)}"
    }
}
