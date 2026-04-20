// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeCalculatorTest {

    @Test fun returns_null_before_window_fills() {
        val g = GradeCalculator(windowMeters = 20.0)
        g.add(0.0, 100.0)
        g.add(5.0, 101.0)
        g.add(10.0, 102.0)
        assertNull(g.currentGradePct())
    }

    @Test fun flat_track_has_zero_grade() {
        val g = GradeCalculator(windowMeters = 20.0)
        var d = 0.0
        while (d <= 30.0) {
            g.add(d, 100.0)
            d += 2.0
        }
        assertEquals(0.0, g.currentGradePct()!!, 1e-6)
    }

    @Test fun ten_percent_climb() {
        val g = GradeCalculator(windowMeters = 20.0)
        // Gain 1 m for every 10 m of run → 10% grade.
        var d = 0.0
        var e = 100.0
        while (d <= 40.0) {
            g.add(d, e)
            d += 2.0
            e += 0.2
        }
        assertEquals(10.0, g.currentGradePct()!!, 0.1)
    }

    @Test fun negative_grade_on_descent() {
        val g = GradeCalculator(windowMeters = 20.0)
        var d = 0.0
        var e = 100.0
        while (d <= 40.0) {
            g.add(d, e)
            d += 2.0
            e -= 0.1 // 5% descent
        }
        assertEquals(-5.0, g.currentGradePct()!!, 0.1)
    }

    @Test fun window_advances_so_old_samples_drop_out() {
        val g = GradeCalculator(windowMeters = 20.0)
        // First 20 m climb at 10%, then next 20 m is flat.
        var d = 0.0
        var e = 100.0
        while (d <= 20.0) { g.add(d, e); d += 2.0; e += 0.2 }
        while (d <= 45.0) { g.add(d, e); d += 2.0 } // flat
        // With the window now covering only the flat section, grade → ~0.
        assertEquals(0.0, g.currentGradePct()!!, 0.5)
    }

    @Test fun reset_clears_state() {
        val g = GradeCalculator(windowMeters = 20.0)
        for (d in 0..40 step 2) g.add(d.toDouble(), 100.0 + d * 0.1)
        g.reset()
        assertNull(g.currentGradePct())
    }
}
