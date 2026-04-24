// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStopDetectorTest {

    private fun sample(tMs: Long, lat: Double = 45.0, lon: Double = -122.0, speed: Double = 1.0) =
        AutoStopDetector.Sample(tMs, lat, lon, speed)

    @Test
    fun `first fix alone does not fire`() {
        val d = AutoStopDetector()
        assertNull(d.feed(sample(0L)))
    }

    @Test
    fun `slow steady walking does not fire spike`() {
        val d = AutoStopDetector()
        var last: AutoStopDetector.Signal? = null
        for (i in 0..60) {
            last = d.feed(sample(i * 1_000L, speed = 1.3))  // ~3 mph
        }
        assertNull(last)
    }

    @Test
    fun `sustained speed spike fires after three consecutive fixes`() {
        val d = AutoStopDetector()
        // 30 s of walking at 1.3 m/s, then 3 fixes at 8 m/s (≈18 mph).
        for (i in 0..29) d.feed(sample(i * 1_000L, speed = 1.3))
        assertNull(d.feed(sample(30_000L, speed = 8.0))) // count=1
        assertNull(d.feed(sample(31_000L, speed = 8.0))) // count=2
        val sig = d.feed(sample(32_000L, speed = 8.0))   // count=3 → fires
        assertNotNull(sig)
        assertEquals(AutoStopDetector.Reason.SPEED_SPIKE, sig!!.reason)
        // Trim timestamp is the last fix BEFORE the spike started.
        assertEquals(29_000L, sig.trimAfterMs)
    }

    @Test
    fun `single spike alone does not fire (noise rejection)`() {
        val d = AutoStopDetector()
        for (i in 0..29) d.feed(sample(i * 1_000L, speed = 1.3))
        assertNull(d.feed(sample(30_000L, speed = 8.0)))  // count=1
        assertNull(d.feed(sample(31_000L, speed = 1.3)))  // resets
        assertNull(d.feed(sample(32_000L, speed = 8.0)))  // count=1 again
        assertNull(d.feed(sample(33_000L, speed = 1.3)))  // resets
    }

    @Test
    fun `spike below absolute floor does not fire even if relatively fast`() {
        val d = AutoStopDetector()
        // Baseline near zero, then 3 fixes at 3 m/s (~7 mph) — below 10 mph floor.
        for (i in 0..29) d.feed(sample(i * 1_000L, speed = 0.3))
        assertNull(d.feed(sample(30_000L, speed = 3.0)))
        assertNull(d.feed(sample(31_000L, speed = 3.0)))
        assertNull(d.feed(sample(32_000L, speed = 3.0)))
    }

    @Test
    fun `returned home requires leaving and coming back`() {
        val d = AutoStopDetector(minDurationMs = 60_000L)  // short for testing
        // Fix 1 at origin.
        d.feed(sample(0L, lat = 45.0, lon = -122.0, speed = 0.5))
        // Stay at origin for 2 min — not yet gone anywhere.
        for (i in 1..120) d.feed(sample(i * 1_000L, lat = 45.0, lon = -122.0, speed = 0.5))
        // Still no signal; user never left the home radius.
        assertNull(d.feed(sample(121_000L, lat = 45.0, lon = -122.0, speed = 0.5)))
    }

    @Test
    fun `returned home fires after loop`() {
        val d = AutoStopDetector(minDurationMs = 60_000L)
        // Start at origin.
        d.feed(sample(0L, lat = 45.0, lon = -122.0, speed = 1.3))
        // Walk away: ~500 m north-ish. 0.005° lat ≈ 555 m.
        for (i in 1..300) {
            d.feed(sample(i * 1_000L, lat = 45.005, lon = -122.0, speed = 1.3))
        }
        // Return to origin, at rest. Duration by now: 301 s > 60 s threshold.
        val sig = d.feed(sample(301_000L, lat = 45.0, lon = -122.0, speed = 0.3))
        assertNotNull(sig)
        assertEquals(AutoStopDetector.Reason.RETURNED_HOME, sig!!.reason)
        assertEquals(301_000L, sig.trimAfterMs)
    }

    @Test
    fun `short loop fires with default settings`() {
        // Regression: earlier we gated returned-home on a 10-min duration,
        // which silently killed the signal for short walks. Default now
        // relies on leftHomeOnce as the sole guard.
        val d = AutoStopDetector()
        d.feed(sample(0L, lat = 45.0, lon = -122.0, speed = 1.3))
        // Walk ~200 m out in 3 minutes.
        for (i in 1..180) {
            d.feed(sample(i * 1_000L, lat = 45.0 + 0.00001 * i, lon = -122.0, speed = 1.3))
        }
        // Back at home, at rest.
        val sig = d.feed(sample(200_000L, lat = 45.0, lon = -122.0, speed = 0.3))
        assertNotNull(sig)
        assertEquals(AutoStopDetector.Reason.RETURNED_HOME, sig!!.reason)
    }
}
