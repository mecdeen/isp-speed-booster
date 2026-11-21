package com.ispbooster.pinger

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings toggle. On = start the booster. Off = stop it and kill the app completely. */
class PingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(PingStats.running)
    }

    override fun onClick() {
        super.onClick()
        if (PingStats.running) {
            render(false)
            PingService.exit(this)
            return
        }
        try {
            PingService.start(this)
            render(true)
        } catch (_: Exception) {
            // Android 12+ can refuse a foreground-service start from the background
            // (mostly when battery optimization is still on). Start it via an invisible activity instead.
            launchStarter()
        }
    }

    private fun launchStarter() {
        val intent = Intent(this, TileLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
