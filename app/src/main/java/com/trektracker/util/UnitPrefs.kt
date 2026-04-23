// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.util

import android.content.Context

/**
 * Single user-facing unit preference. Internal storage and all calculations
 * stay metric (DESIGN.md §Conventions); this only governs what the user sees.
 * Default is IMPERIAL because the initial user base is US-based — the
 * Settings dialog flips it at runtime.
 */
object UnitPrefs {

    @Volatile
    private var cached: DistanceUnit? = null

    fun get(context: Context): DistanceUnit {
        cached?.let { return it }
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_UNIT, DistanceUnit.IMPERIAL.name)
        val unit = runCatching { DistanceUnit.valueOf(raw ?: "") }
            .getOrDefault(DistanceUnit.IMPERIAL)
        cached = unit
        return unit
    }

    fun set(context: Context, unit: DistanceUnit) {
        cached = unit
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UNIT, unit.name)
            .apply()
    }

    private const val PREFS_NAME = "trektracker.units"
    private const val KEY_UNIT = "distanceUnit"
}
