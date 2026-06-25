// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import com.comtekglobal.tromp.util.haversineMeters

/**
 * Replays persisted points into the same summary semantics used by live
 * tracking. Used for crash recovery and for a complete, internally-consistent
 * auto-stop trim.
 */
object SessionStatsCalculator {

    data class Stats(
        val endTime: Long,
        val elapsedMs: Long,
        val movingMs: Long,
        val totalDistanceM: Double,
        val totalAscentM: Double,
        val totalDescentM: Double,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        val maxGradePct: Double,
        val minGradePct: Double,
        val stepCount: Int,
    )

    fun calculate(
        startTime: Long,
        points: List<TrackingSession.Point>,
        endTime: Long = points.lastOrNull()?.tMs ?: startTime,
    ): Stats {
        var distanceM = 0.0
        var movingMs = 0L
        var maxSpeedMps = 0.0
        var maxGradePct = Double.NEGATIVE_INFINITY
        var minGradePct = Double.POSITIVE_INFINITY
        var previous: TrackingSession.Point? = null
        val ascent = AscentAccumulator()
        val grade = GradeCalculator()

        for (point in points) {
            val prior = previous
            if (prior != null && !point.isAutoPaused) {
                distanceM += haversineMeters(prior.lat, prior.lon, point.lat, point.lon)
                val dt = (point.tMs - prior.tMs).coerceAtLeast(0L)
                // A large gap normally means manual pause, provider outage, or
                // process death. Cap it rather than turning the whole gap into
                // moving time.
                movingMs += dt.coerceAtMost(MAX_MOVING_INTERVAL_MS)
            }
            if (!point.isAutoPaused) {
                point.elevM?.let { elevation ->
                    ascent.add(elevation)
                    grade.add(distanceM, elevation)
                    grade.currentGradePct()?.let {
                        maxGradePct = maxOf(maxGradePct, it)
                        minGradePct = minOf(minGradePct, it)
                    }
                }
                maxSpeedMps = maxOf(maxSpeedMps, point.speedMps.toDouble())
            }
            previous = point
        }

        val elapsedMs = (endTime - startTime).coerceAtLeast(0L)
        return Stats(
            endTime = endTime,
            elapsedMs = elapsedMs,
            movingMs = movingMs.coerceAtMost(elapsedMs),
            totalDistanceM = distanceM,
            totalAscentM = ascent.totalAscentM,
            totalDescentM = ascent.totalDescentM,
            avgSpeedMps = if (elapsedMs > 0) distanceM / (elapsedMs / 1000.0) else 0.0,
            maxSpeedMps = maxSpeedMps,
            maxGradePct = maxGradePct.takeUnless { it == Double.NEGATIVE_INFINITY } ?: 0.0,
            minGradePct = minGradePct.takeUnless { it == Double.POSITIVE_INFINITY } ?: 0.0,
            stepCount = points.lastOrNull()?.cumStepCount ?: 0,
        )
    }

    private const val MAX_MOVING_INTERVAL_MS = 5_000L
}
