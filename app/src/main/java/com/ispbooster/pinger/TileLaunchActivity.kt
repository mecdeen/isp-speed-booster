package com.ispbooster.pinger

import android.app.Activity
import android.os.Bundle

/** Invisible trampoline: being a foreground activity lets it start the service, then it finishes at once. */
class TileLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { PingService.start(this) }
        PingService.refreshTile(this)
        finish()
    }
}
