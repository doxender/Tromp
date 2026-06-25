// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatsCalculatorTest {

    private fun point(
        time: Long,
        lat: Double,
        elevation: Double,
        speed: Float,
        steps: Int,
        autoPaused: Boolean = false,
    ) = TrackingSession.Point(
        lat = lat,
        lon = -122.0,
        elevM = elevation,
        gpsElevM = elevation,
        pressureHpa = null,
        horizAccM = 3f,
        speedMps = speed,
        bearingDeg = null,
        cumStepCount = steps,
        isAutoPaused = autoPaused,
        tMs = time,
    )

    @Test
    fun `trim replay removes trailing speed and steps`() {
        val start = 1_000L
        val kept = listOf(
            point(start, 45.0, 100.0, 1f, 0),
            point(start + 2_000L, 45.001, 104.0, 1.5f, 3),
        )
        val stats = SessionStatsCalculator.calculate(
            startTime = start,
            points = kept,
            endTime = start + 2_000L,
        )

        assertEquals(3, stats.stepCount)
        assertEquals(1.5, stats.maxSpeedMps, 0.001)
        assertTrue(stats.totalDistanceM in 110.0..112.5)
        assertEquals(4.0, stats.totalAscentM, 0.001)
    }

    @Test
    fun `auto paused fixes do not add distance ascent or max speed`() {
        val points = listOf(
            point(0L, 45.0, 100.0, 1f, 0),
            point(2_000L, 45.010, 150.0, 20f, 5, autoPaused = true),
            point(4_000L, 45.011, 104.0, 1.5f, 8),
        )
        val stats = SessionStatsCalculator.calculate(0L, points, 4_000L)

        assertEquals(1.5, stats.maxSpeedMps, 0.001)
        assertTrue(stats.totalDistanceM in 110.0..112.5)
        assertEquals(4.0, stats.totalAscentM, 0.001)
    }

    @Test
    fun `large gaps are not counted wholesale as moving time`() {
        val points = listOf(
            point(0L, 45.0, 100.0, 1f, 0),
            point(60_000L, 45.001, 100.0, 1f, 1),
        )
        val stats = SessionStatsCalculator.calculate(0L, points, 60_000L)
        assertEquals(5_000L, stats.movingMs)
    }
}
