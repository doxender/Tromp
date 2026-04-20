// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HaversineTest {

    @Test fun zero_distance_for_identical_points() {
        assertEquals(0.0, haversineMeters(37.0, -122.0, 37.0, -122.0), 1e-6)
    }

    @Test fun approx_one_degree_latitude_is_about_111km() {
        // At the equator, one degree of latitude ≈ 111,195 m.
        val d = haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 200.0)
    }

    @Test fun sf_to_la_is_approximately_560km() {
        // San Francisco (37.7749, -122.4194) to Los Angeles (34.0522, -118.2437)
        val d = haversineMeters(37.7749, -122.4194, 34.0522, -118.2437)
        assertEquals(559_000.0, d, 3_000.0)
    }

    @Test fun symmetric() {
        val a = haversineMeters(40.0, -74.0, 51.5, -0.12)
        val b = haversineMeters(51.5, -0.12, 40.0, -74.0)
        assertEquals(a, b, 1e-6)
    }
}
