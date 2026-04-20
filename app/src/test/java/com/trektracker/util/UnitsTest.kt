// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test fun meters_to_feet() {
        assertEquals(3.28084, 1.0.metersToFeet(), 1e-4)
        assertEquals(100.0, 30.48.metersToFeet(), 1e-4)
    }

    @Test fun meters_to_km_and_miles() {
        assertEquals(1.0, 1000.0.metersToKm(), 1e-9)
        assertEquals(1.0, 1609.344.metersToMiles(), 1e-9)
    }

    @Test fun mps_to_kmh_and_mph() {
        assertEquals(36.0, 10.0.mpsToKmh(), 1e-4)
        assertEquals(22.369, 10.0.mpsToMph(), 1e-2)
    }

    @Test fun format_distance_metric_crosses_km_boundary() {
        assertEquals("500 m", formatDistance(500.0, DistanceUnit.METRIC))
        assertEquals("1.50 km", formatDistance(1500.0, DistanceUnit.METRIC))
    }

    @Test fun format_distance_imperial_crosses_mile_boundary() {
        assertEquals("500 ft", formatDistance(500.0 * METERS_PER_FOOT, DistanceUnit.IMPERIAL))
        assertEquals("2.00 mi", formatDistance(2.0 * METERS_PER_MILE, DistanceUnit.IMPERIAL))
    }

    @Test fun format_pace_zero_speed_returns_dash() {
        assertEquals("—", formatPace(0.0, DistanceUnit.METRIC))
        assertEquals("—", formatPace(-1.0, DistanceUnit.METRIC))
    }

    @Test fun format_pace_10kmh_is_6min_per_km() {
        // 10 km/h = 2.7778 m/s → 6:00 /km
        val mps = 10.0 / 3.6
        assertEquals("6:00 /km", formatPace(mps, DistanceUnit.METRIC))
    }
}
