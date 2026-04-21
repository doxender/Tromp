// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStopTrimmerTest {

    private fun pt(tMs: Long, lat: Double, lon: Double, elev: Double? = null) =
        TrackingSession.Point(
            lat = lat, lon = lon,
            elevM = elev, gpsElevM = elev,
            pressureHpa = null,
            horizAccM = 0f,
            tMs = tMs,
        )

    @Test
    fun `trim drops points after cutoff`() {
        val pts = listOf(
            pt(0L,   45.0, -122.0),
            pt(10L,  45.001, -122.0),
            pt(20L,  45.002, -122.0),
            pt(30L,  45.003, -122.0),
        )
        val t = AutoStopTrimmer.trim(pts, trimAfterMs = 20L)
        assertEquals(3, t.keptPoints.size)
        assertEquals(1, t.droppedPointCount)
    }

    @Test
    fun `kept distance matches haversine sum`() {
        val pts = listOf(
            pt(0L, 45.0, -122.0),
            pt(10L, 45.001, -122.0),
            pt(20L, 45.002, -122.0),
        )
        val t = AutoStopTrimmer.trim(pts, trimAfterMs = 20L)
        // 0.001° lat ≈ 111 m; two such hops ≈ 222 m. Tolerance 2 m.
        assertTrue("got ${t.totalDistanceM}", t.totalDistanceM in 220.0..224.0)
    }

    @Test
    fun `dropped distance is measured from last kept fix through the trailing points`() {
        val pts = listOf(
            pt(0L, 45.0, -122.0),
            pt(10L, 45.0, -122.0),
            pt(20L, 45.001, -122.0),   // 111 m
            pt(30L, 45.002, -122.0),   // another 111 m, total dropped ≈ 222 m
        )
        val t = AutoStopTrimmer.trim(pts, trimAfterMs = 10L)
        assertEquals(2, t.keptPoints.size)
        assertEquals(2, t.droppedPointCount)
        assertTrue("got ${t.droppedDistanceM}", t.droppedDistanceM in 220.0..224.0)
    }

    @Test
    fun `ascent is recomputed from kept elevations`() {
        val pts = listOf(
            pt(0L,  45.0, -122.0, elev = 100.0),
            pt(10L, 45.0, -122.0, elev = 104.0),  // +4 (past 3 m hysteresis)
            pt(20L, 45.0, -122.0, elev = 104.0),
            pt(30L, 45.0, -122.0, elev = 120.0),  // not kept
        )
        val t = AutoStopTrimmer.trim(pts, trimAfterMs = 20L)
        assertEquals(3, t.keptPoints.size)
        assertEquals(4.0, t.totalAscentM, 0.001)
    }

    @Test
    fun `trimming to before any point yields empty totals`() {
        val pts = listOf(
            pt(10L, 45.0, -122.0, elev = 100.0),
            pt(20L, 45.001, -122.0, elev = 105.0),
        )
        val t = AutoStopTrimmer.trim(pts, trimAfterMs = 0L)
        assertEquals(0, t.keptPoints.size)
        assertEquals(0.0, t.totalDistanceM, 0.001)
        assertEquals(0.0, t.totalAscentM, 0.001)
    }
}
