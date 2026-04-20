// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoPauseDetectorTest {

    @Test fun starts_in_moving_state() {
        val d = AutoPauseDetector()
        assertEquals(AutoPauseDetector.State.MOVING, d.state)
    }

    @Test fun transitions_to_paused_after_sustained_stillness() {
        val d = AutoPauseDetector(pauseStillMs = 30_000L)
        d.onSample(0L, 0.1)
        d.onSample(29_000L, 0.1)
        assertEquals(AutoPauseDetector.State.MOVING, d.state)
        d.onSample(30_000L, 0.1)
        assertEquals(AutoPauseDetector.State.PAUSED, d.state)
    }

    @Test fun a_single_movement_sample_resets_the_still_timer() {
        val d = AutoPauseDetector(pauseStillMs = 30_000L)
        d.onSample(0L, 0.1)
        d.onSample(25_000L, 0.1)
        d.onSample(26_000L, 2.0) // resets stillSince
        d.onSample(55_000L, 0.1) // only 29 s of stillness since reset
        assertEquals(AutoPauseDetector.State.MOVING, d.state)
    }

    @Test fun transitions_to_moving_after_sustained_motion_from_paused() {
        val d = AutoPauseDetector(
            pauseStillMs = 30_000L,
            resumeMovingMs = 3_000L,
        )
        // Force into PAUSED.
        d.onSample(0L, 0.0)
        d.onSample(31_000L, 0.0)
        assertEquals(AutoPauseDetector.State.PAUSED, d.state)

        d.onSample(40_000L, 1.5)
        d.onSample(42_000L, 1.5)
        assertEquals(AutoPauseDetector.State.PAUSED, d.state)
        d.onSample(43_000L, 1.5)
        assertEquals(AutoPauseDetector.State.MOVING, d.state)
    }

    @Test fun reset_returns_to_moving() {
        val d = AutoPauseDetector(pauseStillMs = 30_000L)
        d.onSample(0L, 0.0)
        d.onSample(31_000L, 0.0)
        d.reset()
        assertEquals(AutoPauseDetector.State.MOVING, d.state)
    }
}
