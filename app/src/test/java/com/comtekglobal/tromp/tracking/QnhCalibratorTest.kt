// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class QnhCalibratorTest {

    // SensorManager.getAltitude is an Android framework call, not callable
    // in local unit tests. Verify the inverse formula round-trips via the
    // ISA formula directly.
    private fun isaAltitude(p0: Double, p: Double): Double =
        44330.0 * (1.0 - Math.pow(p / p0, 1.0 / 5.255))

    @Test fun sea_level_pressure_for_zero_altitude_equals_reading() {
        val p = 1013.25
        val qnh = computeQnhHpa(p, 0.0)
        assertEquals(p, qnh, 1e-6)
    }

    @Test fun round_trip_via_isa_formula() {
        val measuredP = 950.0
        val benchmarkElev = 500.0
        val qnh = computeQnhHpa(measuredP, benchmarkElev)
        val recoveredElev = isaAltitude(qnh, measuredP)
        assertEquals(benchmarkElev, recoveredElev, 1e-2)
    }

    @Test fun higher_altitude_implies_higher_sea_level_pressure_for_same_reading() {
        val p = 900.0
        val qnhLow = computeQnhHpa(p, 300.0)
        val qnhHigh = computeQnhHpa(p, 1000.0)
        assert(qnhHigh > qnhLow) {
            "Expected qnh to grow with benchmark altitude (lower was $qnhLow, higher was $qnhHigh)"
        }
    }
}
