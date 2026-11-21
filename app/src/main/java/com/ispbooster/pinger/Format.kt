package com.ispbooster.pinger

import java.util.Locale

/** 30000 -> "30", 500 -> "0.5", 100 -> "0.1" */
fun formatSeconds(ms: Long): String =
    if (ms % 1000 == 0L) (ms / 1000).toString()
    else String.format(Locale.US, "%.1f", ms / 1000.0).trimEnd('0').trimEnd('.')
