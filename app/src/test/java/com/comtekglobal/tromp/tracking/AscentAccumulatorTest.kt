// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class AscentAccumulatorTest {

    @Test fun ignores_noise_below_threshold() {
        val a = AscentAccumulator(thresholdMeters = 3.0)
        a.add(100.0)
        a.add(101.0)
        a.add(100.5)
        a.add(101.5)
        a.add(100.0)
        assertEquals(0.0, a.totalAscentM, 1e-9)
        assertEquals(0.0, a.totalDescentM, 1e-9)
    }

    @Test fun accumulates_sustained_climb() {
        val a = AscentAccumulator(thresholdMeters = 3.0)
        a.add(100.0)
        a.add(105.0) // +5, commits
        a.add(110.0) // +5, commits
        a.add(115.0) // +5, commits
        assertEquals(15.0, a.totalAscentM, 1e-9)
        assertEquals(0.0, a.totalDescentM, 1e-9)
    }

    @Test fun accumulates_sustained_descent() {
        val a = AscentAccumulator(thresholdMeters = 3.0)
        a.add(200.0)
        a.add(196.0) // −4, commits
        a.add(191.0) // −5, commits
        assertEquals(0.0, a.totalAscentM, 1e-9)
        assertEquals(9.0, a.totalDescentM, 1e-9)
    }

    @Test fun reversal_does_not_count_noise_as_climb() {
        // Mild oscillation within the dead zone must never commit.
        val a = AscentAccumulator(thresholdMeters = 3.0)
        a.add(100.0)
        for (i in 0..100) {
            a.add(100.0 + (if (i % 2 == 0) 2.0 else -2.0))
        }
        assertEquals(0.0, a.totalAscentM, 1e-9)
        assertEquals(0.0, a.totalDescentM, 1e-9)
    }

    @Test fun climb_then_descent() {
        val a = AscentAccumulator(thresholdMeters = 3.0)
        a.add(100.0)
        a.add(110.0) // +10 ascent
        a.add(104.0) // reversal → anchor resets at 104, nothing counted yet
        a.add(100.0) // −4 from new anchor → descent +4
        assertEquals(10.0, a.totalAscentM, 1e-9)
        assertEquals(4.0, a.totalDescentM, 1e-9)
    }

    @Test fun reset_clears_totals() {
        val a = AscentAccumulator(thresholdMeters = 3.0)
        a.add(100.0); a.add(110.0)
        a.reset()
        assertEquals(0.0, a.totalAscentM, 1e-9)
        assertEquals(0.0, a.totalDescentM, 1e-9)
    }
}
