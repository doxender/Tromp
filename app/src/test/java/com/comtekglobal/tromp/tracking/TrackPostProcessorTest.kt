// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import com.comtekglobal.tromp.tracking.TrackPostProcessor.Sample
import com.comtekglobal.tromp.tracking.TrackPostProcessor.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPostProcessorTest {

    /**
     * Build a sample stream at a fixed dt with linearly accumulating steps
     * and a constant altitude. dt defaults to 1 s — gives the classifier a
     * full 15-fix window centered on each interior sample.
     */
    private fun stream(
        speeds: List<Double>,
        dtMs: Long = 1_000L,
        startTMs: Long = 0L,
        stepsPerSec: Int = 0,
        altMps: Double = 0.0,
    ): List<Sample> {
        var t = startTMs
        var steps = 0
        var alt = 0.0
        return speeds.map { s ->
            val sample = Sample(
                tMs = t,
                speedMps = s,
                altM = alt,
                cumStepCount = steps,
            )
            t += dtMs
            steps += stepsPerSec
            alt += altMps * (dtMs / 1000.0)
            sample
        }
    }

    @Test fun empty_input_yields_empty_output() {
        assertEquals(emptyList<TrackPostProcessor.Classification>(), TrackPostProcessor.classify(emptyList()))
    }

    @Test fun single_fix_defaults_to_dawdling() {
        // No neighbors → no Δt → no cadence/vrate signal. Per CONTEXT.md
        // item 7, single-fix windows fall through to DAWDLING.
        val out = TrackPostProcessor.classify(listOf(Sample(0L, 1.5, 100.0, 0)))
        assertEquals(1, out.size)
        assertEquals(State.DAWDLING, out[0].state)
        assertEquals("dawdle_no_window", out[0].reason)
    }

    @Test fun steady_brisk_walk_classifies_as_active() {
        // 1.4 m/s ≈ 5 km/h normal walking pace, very stable. Active rule
        // wins: avg ≥ 0.6 AND cv ≤ 0.6.
        val pts = stream(List(30) { 1.4 }, stepsPerSec = 2)
        val out = TrackPostProcessor.classify(pts)
        // Skip the very first / last (smaller windows). Centre samples
        // should all be ACTIVE.
        for (i in 5 until 25) {
            assertEquals("idx=$i state", State.ACTIVE, out[i].state)
        }
    }

    @Test fun standing_still_with_no_motion_is_dawdling() {
        // Speed near zero, no steps, no altitude change. Fails ACTIVE
        // (avg < 0.6). Cadence + vrate both below floors → DAWDLING.
        val pts = stream(List(30) { 0.05 })
        val out = TrackPostProcessor.classify(pts)
        for (i in 5 until 25) {
            assertEquals("idx=$i state", State.DAWDLING, out[i].state)
        }
    }

    @Test fun slow_climb_with_cadence_is_clambering() {
        // Slow speed (below ACTIVE floor) but steps still being taken
        // briskly + climbing. Should be CLAMBERING — careful effortful
        // motion, the rocky-scramble case Dan called out.
        val pts = stream(
            speeds = List(30) { 0.4 },
            stepsPerSec = 1,         // 60 spm > 30 spm floor
            altMps = 0.2,            // 0.2 m/s vrate > 0.1 floor
        )
        val out = TrackPostProcessor.classify(pts)
        for (i in 5 until 25) {
            assertEquals("idx=$i state", State.CLAMBERING, out[i].state)
        }
    }

    @Test fun erratic_speed_above_floor_is_not_active() {
        // Avg speed well above 0.6, but high CV (alternating fast/slow).
        // Fails ACTIVE on stability. With no cadence + no altitude change
        // it falls through to DAWDLING (erratic GPS jitter without effort
        // signals — typical of a stationary user with noisy fixes).
        val pts = stream(
            speeds = List(30) { i -> if (i % 2 == 0) 0.2 else 2.5 },
        )
        val out = TrackPostProcessor.classify(pts)
        for (i in 5 until 25) {
            assertTrue("idx=$i should not be ACTIVE", out[i].state != State.ACTIVE)
        }
    }

    @Test fun erratic_speed_with_cadence_is_clambering() {
        // Same speed pattern as above but with steady cadence — that
        // promotes it from DAWDLING to CLAMBERING because the user is
        // demonstrably moving.
        val pts = stream(
            speeds = List(30) { i -> if (i % 2 == 0) 0.2 else 2.5 },
            stepsPerSec = 1,
        )
        val out = TrackPostProcessor.classify(pts)
        for (i in 5 until 25) {
            assertEquals("idx=$i state", State.CLAMBERING, out[i].state)
        }
    }

    @Test fun descent_without_steps_still_clambers() {
        // Sliding / careful descent: low speed, no step counter advance,
        // but altitude steadily falling. |vrate| ≥ 0.1 m/s alone is enough
        // to promote to CLAMBERING.
        val pts = stream(
            speeds = List(30) { 0.4 },
            stepsPerSec = 0,
            altMps = -0.5,       // descending fast
        )
        val out = TrackPostProcessor.classify(pts)
        for (i in 5 until 25) {
            assertEquals("idx=$i state", State.CLAMBERING, out[i].state)
        }
    }

    @Test fun signals_block_carries_diagnostic_numbers() {
        // The CSV diagnostic columns read out of WindowSignals; sanity-check
        // the obvious accessors for a centre sample.
        val pts = stream(List(30) { 1.4 }, stepsPerSec = 2, altMps = 0.05)
        val out = TrackPostProcessor.classify(pts)
        val mid = out[15].signals
        assertNotNull(mid.avgSpeedMps)
        assertEquals(1.4, mid.avgSpeedMps!!, 1e-6)
        assertNotNull(mid.speedCv)
        assertEquals(0.0, mid.speedCv!!, 1e-6)         // constant speed → CV = 0
        assertNotNull(mid.avgCadenceSpm)
        assertEquals(120.0, mid.avgCadenceSpm!!, 1e-6) // 2 steps/s → 120 spm
        assertNotNull(mid.avgVrateMps)
        assertEquals(0.05, mid.avgVrateMps!!, 1e-6)
    }

    @Test fun just_above_active_speed_floor_with_stable_speed_is_active() {
        // 0.01 m/s above the floor avoids the floating-point edge: 15 doubles
        // each holding the literal 0.6 average to 0.5999999..., which fails
        // the exact `>= 0.6` check. That's the right rule (we don't want to
        // fuzz the comparison) but it means a boundary test must use a value
        // safely on the inside.
        val pts = stream(
            speeds = List(30) { TrackPostProcessor.ACTIVE_SPEED_FLOOR_MPS + 0.01 },
            stepsPerSec = 1,
        )
        val out = TrackPostProcessor.classify(pts)
        assertEquals(State.ACTIVE, out[15].state)
    }

    @Test fun just_below_active_speed_floor_with_no_motion_is_dawdling() {
        // 0.59 m/s, stable, no cadence, flat altitude. Just below the
        // floor → fails ACTIVE. No cadence/vrate signals → DAWDLING.
        val pts = stream(
            speeds = List(30) { 0.59 },
            stepsPerSec = 0,
            altMps = 0.0,
        )
        val out = TrackPostProcessor.classify(pts)
        assertEquals(State.DAWDLING, out[15].state)
    }

    @Test fun mixed_sequence_classifies_per_window_not_globally() {
        // First half DAWDLING (slow, no motion), second half ACTIVE (brisk
        // walk). The window straddling the transition will have mixed
        // signal but each side, well inside its half, should match.
        val first = stream(List(20) { 0.05 }, stepsPerSec = 0)
        val secondStart = first.last().tMs + 1_000L
        val second = stream(
            List(20) { 1.4 },
            startTMs = secondStart,
            stepsPerSec = 2,
        ).map { it.copy(cumStepCount = it.cumStepCount + first.last().cumStepCount) }
        val pts = first + second
        val out = TrackPostProcessor.classify(pts)
        // Index 2 is well inside the still half (window covers indices 0..9).
        assertEquals(State.DAWDLING, out[2].state)
        // Index 30 is well inside the active half.
        assertEquals(State.ACTIVE, out[30].state)
    }

    @Test fun gap_in_timestamps_does_not_pull_distant_neighbours_into_window() {
        // Two clusters of 5 fixes each, separated by 60 s. A fix in cluster
        // A should see only its 5 neighbours, not the cluster B fixes.
        val a = stream(List(5) { 1.4 }, stepsPerSec = 2)
        val b = stream(List(5) { 0.05 }, startTMs = 60_000L, stepsPerSec = 0)
        val out = TrackPostProcessor.classify(a + b)
        // The middle of cluster A: window covers all of A (5 fixes), no B.
        val midA = out[2].signals
        assertEquals(5, midA.sampleCount)
        // The middle of cluster B: similarly only 5 fixes from B.
        val midB = out[7].signals
        assertEquals(5, midB.sampleCount)
        // And classification reflects each cluster's own signal.
        assertEquals(State.ACTIVE, out[2].state)
        assertEquals(State.DAWDLING, out[7].state)
    }

    @Test fun unsorted_timestamps_handled_only_for_sorted_input() {
        // Documented assumption: classify expects timestamp-sorted input.
        // We don't assert behavior on shuffled input — the in-tree call
        // sites (TrackingService points list, Room ORDER BY seq) always
        // pass sorted samples. If a future caller shuffles input the
        // classifier will produce garbage; this test exists only to flag
        // the assumption explicitly.
        val sorted = stream(List(10) { 1.4 }, stepsPerSec = 2)
        val out = TrackPostProcessor.classify(sorted)
        // Sorted input produces a defined signal. Just confirm it ran.
        assertEquals(10, out.size)
        assertNull(out[0].signals.speedCv?.let { if (it.isNaN()) it else null })
    }
}
