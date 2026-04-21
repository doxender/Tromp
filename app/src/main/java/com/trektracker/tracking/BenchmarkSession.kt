// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import android.content.Context

/**
 * Holds the most recently acquired benchmark. The benchmark record (location
 * + elevation + source + timestamp) is persisted to SharedPreferences on
 * Accept and restored on app launch so proximity-based staleness checks
 * survive process death. QNH is kept in memory only — weather drift makes it
 * stale within hours, so forcing recalibration on each cold start is safer
 * than trusting a persisted value.
 */
object BenchmarkSession {

    data class Benchmark(
        val lat: Double,
        val lon: Double,
        val elevM: Double,
        val source: String,
        val horizAccM: Double,
        val fixCount: Int,
        val baroAvgHpa: Double?,
        val baroSampleCount: Int,
        val acquiredAtMs: Long,
    )

    @Volatile
    var current: Benchmark? = null

    @Volatile
    var qnhHpa: Double? = null

    /** Persist the current benchmark (if any) to SharedPreferences. */
    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val b = current
        if (b == null) {
            prefs.clear().apply()
            return
        }
        prefs
            .putLong(KEY_ACQUIRED_AT, b.acquiredAtMs)
            .putFloat(KEY_LAT, b.lat.toFloat())
            .putFloat(KEY_LON, b.lon.toFloat())
            .putFloat(KEY_ELEV_M, b.elevM.toFloat())
            .putString(KEY_SOURCE, b.source)
            .putFloat(KEY_HORIZ_ACC_M, b.horizAccM.toFloat())
            .putInt(KEY_FIX_COUNT, b.fixCount)
            .putFloat(
                KEY_BARO_AVG_HPA,
                (b.baroAvgHpa ?: Double.NaN).toFloat(),
            )
            .putInt(KEY_BARO_SAMPLE_COUNT, b.baroSampleCount)
            .apply()
    }

    /** Restore a previously persisted benchmark into `current`. QNH is not restored. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ACQUIRED_AT)) return
        val baroAvg = prefs.getFloat(KEY_BARO_AVG_HPA, Float.NaN)
        current = Benchmark(
            lat = prefs.getFloat(KEY_LAT, 0f).toDouble(),
            lon = prefs.getFloat(KEY_LON, 0f).toDouble(),
            elevM = prefs.getFloat(KEY_ELEV_M, 0f).toDouble(),
            source = prefs.getString(KEY_SOURCE, "") ?: "",
            horizAccM = prefs.getFloat(KEY_HORIZ_ACC_M, 0f).toDouble(),
            fixCount = prefs.getInt(KEY_FIX_COUNT, 0),
            baroAvgHpa = if (baroAvg.isNaN()) null else baroAvg.toDouble(),
            baroSampleCount = prefs.getInt(KEY_BARO_SAMPLE_COUNT, 0),
            acquiredAtMs = prefs.getLong(KEY_ACQUIRED_AT, 0L),
        )
    }

    fun clear() {
        current = null
        qnhHpa = null
    }

    private const val PREFS_NAME = "trektracker.benchmark"
    private const val KEY_ACQUIRED_AT = "acquiredAtMs"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ELEV_M = "elevM"
    private const val KEY_SOURCE = "source"
    private const val KEY_HORIZ_ACC_M = "horizAccM"
    private const val KEY_FIX_COUNT = "fixCount"
    private const val KEY_BARO_AVG_HPA = "baroAvgHpa"
    private const val KEY_BARO_SAMPLE_COUNT = "baroSampleCount"
}
