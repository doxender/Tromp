// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

/**
 * Holds the most recently acquired benchmark for the current app session.
 * Not persisted — the value is consumed when an activity is started (written
 * into ActivityEntity.benchmarkElevM) and discarded on process death.
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

    /**
     * QNH goes stale as weather pressure systems move — Decision Log row 5.
     * Four hours is a pragmatic window: long enough for a "benchmark once,
     * record several activities" day, short enough that a pressure swing of
     * a few hPa (tens of meters of altitude error) is unlikely.
     */
    const val STALE_THRESHOLD_MS: Long = 4L * 60L * 60L * 1000L

    /** True if there's a calibrated QNH that is older than the staleness window. */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (qnhHpa == null) return false
        val b = current ?: return false
        return nowMs - b.acquiredAtMs > STALE_THRESHOLD_MS
    }

    fun clear() {
        current = null
        qnhHpa = null
    }
}
