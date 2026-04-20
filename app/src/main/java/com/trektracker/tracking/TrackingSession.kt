// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

/**
 * In-memory buffer of the fixes recorded during the current (or most recent)
 * tracking session and the final snapshot emitted by TrackingService on stop.
 * Summary + Map screens read from here. Not persisted — Room hookup pending.
 */
object TrackingSession {

    data class Point(
        val lat: Double,
        val lon: Double,
        val elevM: Double?,          // best available altitude (baro if calibrated, else GPS)
        val gpsElevM: Double?,       // raw GPS altitude, preserved for comparison
        val pressureHpa: Double?,    // barometer reading at this fix, if any
        val horizAccM: Float,        // GPS horizontal accuracy reported for this fix
        val tMs: Long,
    )

    private val _points: MutableList<Point> = mutableListOf()

    @Volatile
    var lastSnapshot: TrackSnapshot? = null

    @Synchronized
    fun append(p: Point) { _points.add(p) }

    @Synchronized
    fun points(): List<Point> = _points.toList()

    @Synchronized
    fun reset() {
        _points.clear()
        lastSnapshot = null
    }
}
