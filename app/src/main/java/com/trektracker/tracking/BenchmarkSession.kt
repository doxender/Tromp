// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import android.content.Context
import com.trektracker.util.METERS_PER_FOOT
import com.trektracker.util.haversineMeters

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

    /** Decision Log row 5: benchmark/QNH older than this is considered stale. */
    const val STALE_THRESHOLD_MS: Long = 60L * 60L * 1000L // 1 hour

    /** Decision Log row 5: a benchmark taken farther than this from here is stale. */
    val PROXIMITY_THRESHOLD_M: Double = 100.0 * METERS_PER_FOOT

    /** Outcome of a staleness check against the current location. */
    sealed class Freshness {
        /** Benchmark is within the time and distance windows; tracking can start. */
        object Fresh : Freshness()

        /** No benchmark record at all — a full Acquire Benchmark flow is needed. */
        object NoBenchmark : Freshness()

        /**
         * A benchmark exists and the user is still within 100 ft of it, but it
         * is older than the time window (or was never calibrated). The stored
         * elevation is still valid; only the barometer needs re-calibration.
         */
        object StaleNearby : Freshness()

        /**
         * A benchmark exists but the user has moved more than 100 ft from it
         * (or the current location cannot be determined). The elevation is no
         * longer reliable; a full Acquire Benchmark is needed.
         */
        object StaleNeedsFull : Freshness()
    }

    /**
     * Decide whether the current benchmark is fresh enough to start tracking
     * without a warning, or — if not — whether a fast barometer-only
     * recalibration is enough or a full rebenchmark is required.
     *
     * `currentLat`/`currentLon` should be the user's current location (e.g.
     * from `FusedLocationProviderClient.getLastLocation`). If unavailable,
     * pass null: the check can't verify proximity and will conservatively
     * treat any stale benchmark as needing a full rebenchmark.
     */
    fun check(
        currentLat: Double?,
        currentLon: Double?,
        nowMs: Long = System.currentTimeMillis(),
    ): Freshness {
        val b = current ?: return Freshness.NoBenchmark

        val distanceM: Double? = if (currentLat != null && currentLon != null) {
            haversineMeters(b.lat, b.lon, currentLat, currentLon)
        } else null
        val withinDistance: Boolean = distanceM != null && distanceM <= PROXIMITY_THRESHOLD_M
        val withinTime: Boolean = (nowMs - b.acquiredAtMs) <= STALE_THRESHOLD_MS
        val calibrated: Boolean = qnhHpa != null

        return when {
            !withinDistance -> Freshness.StaleNeedsFull
            withinTime && calibrated -> Freshness.Fresh
            else -> Freshness.StaleNearby
        }
    }

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
