// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import com.trektracker.util.haversineMeters

/**
 * Pure trim + replay for auto-stop. Given the full list of accepted points
 * and a `trimAfterMs` cutoff, returns a new list containing only points
 * with `tMs <= trimAfterMs` plus recomputed totals (distance + ascent +
 * descent). Grade maxima/minima are not recomputed here — those would
 * require replaying `GradeCalculator`; phase 1 accepts a minor loss of
 * precision on grade after a trim.
 */
object AutoStopTrimmer {

    data class Totals(
        val keptPoints: List<TrackingSession.Point>,
        val totalDistanceM: Double,
        val totalAscentM: Double,
        val totalDescentM: Double,
        val droppedPointCount: Int,
        val droppedDistanceM: Double,
    )

    fun trim(
        allPoints: List<TrackingSession.Point>,
        trimAfterMs: Long,
        ascentThresholdM: Double = 3.0,
    ): Totals {
        val kept = allPoints.filter { it.tMs <= trimAfterMs }
        val dropped = allPoints.size - kept.size

        var dist = 0.0
        var prevLat: Double? = null
        var prevLon: Double? = null
        val ascent = AscentAccumulator(ascentThresholdM)
        for (p in kept) {
            val pl = prevLat
            val plo = prevLon
            if (pl != null && plo != null) {
                dist += haversineMeters(pl, plo, p.lat, p.lon)
            }
            prevLat = p.lat
            prevLon = p.lon
            p.elevM?.let { ascent.add(it) }
        }

        var droppedDist = 0.0
        var dpLat: Double? = kept.lastOrNull()?.lat
        var dpLon: Double? = kept.lastOrNull()?.lon
        for (p in allPoints.drop(kept.size)) {
            val dl = dpLat
            val dlo = dpLon
            if (dl != null && dlo != null) {
                droppedDist += haversineMeters(dl, dlo, p.lat, p.lon)
            }
            dpLat = p.lat
            dpLon = p.lon
        }

        return Totals(
            keptPoints = kept,
            totalDistanceM = dist,
            totalAscentM = ascent.totalAscentM,
            totalDescentM = ascent.totalDescentM,
            droppedPointCount = dropped,
            droppedDistanceM = droppedDist,
        )
    }
}
