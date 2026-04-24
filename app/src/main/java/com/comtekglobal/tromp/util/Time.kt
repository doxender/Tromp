// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "01:23:45" or "23:45" depending on whether hours are nonzero. */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** "2026-04-19 14:32" in the device's local time zone. */
fun formatLocalIsoMinute(epochMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date(epochMs))
}

/** Default activity name: e.g. "Hike · 2026-04-19 14:32". */
fun defaultActivityName(type: String, startEpochMs: Long): String {
    val typeLabel = type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    return "$typeLabel · ${formatLocalIsoMinute(startEpochMs)}"
}
