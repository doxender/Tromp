// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Post-hoc classifier that assigns each fix in a recorded session a state of
 * ACTIVE (hiking), CLAMBERING (slow / careful effort — rocky scrambles,
 * delicate descent), or DAWDLING (puttering / standing still). Pure logic
 * so it can be unit-tested against synthetic fix streams.
 *
 * v1.15.1 wires this only into the diagnostic CSV export (pre-trim +
 * post-trim files saved with each activity). Activity totals, the map
 * polyline, and Room state persistence are deliberately untouched while
 * Dan tunes the thresholds against real recordings. Once the rule is
 * validated, the full rollout in CONTEXT.md "Pending discussion" item 1–9
 * lands as a separate change.
 *
 * Algorithm (CONTEXT.md "Classification rule Dan specified 2026-05-04"):
 *   For each fix at index `i`, build a window of every fix whose timestamp
 *   falls within ±[WINDOW_HALF_MS] of `samples[i].tMs`. Compute four
 *   per-window signals from the fixes that fall inside:
 *     - avg_speed_mps   : mean of `samples[j].speedMps` (Doppler from
 *                          `loc.speed`, NOT haversine-derived — see
 *                          CONTEXT.md item 6)
 *     - speed_cv        : stddev / avg_speed (coefficient of variation;
 *                          ratio so unitless)
 *     - avg_cadence_spm : mean of per-pair `(Δsteps / Δt) * 60` over
 *                          adjacent pairs in the window
 *     - avg_vrate_mps   : mean of per-pair `Δalt / Δt` over adjacent pairs
 *                          (signed — sign carries climbing/descending)
 *
 *   Apply the rules, first match wins:
 *     ACTIVE     ← avg_speed ≥ 0.6 m/s AND speed_cv ≤ 0.6
 *     CLAMBERING ← (NOT ACTIVE) AND (avg_cadence ≥ 30 spm OR |avg_vrate| ≥ 0.1 m/s)
 *     DAWDLING   ← otherwise
 *
 *   Single-fix windows have no neighbor pairs → cadence/vrate undefined →
 *   default DAWDLING (per CONTEXT.md item 7).
 */
object TrackPostProcessor {

    enum class State { ACTIVE, CLAMBERING, DAWDLING }

    /**
     * Minimal classifier input. Decoupled from `TrackPointEntity` /
     * `TrackingSession.Point` so unit tests don't need to instantiate Room
     * entities or the tracking session singleton.
     */
    data class Sample(
        val tMs: Long,
        val speedMps: Double,
        val altM: Double,
        val cumStepCount: Int,
    )

    /** Per-window signal block, surfaced for diagnostic CSV columns. */
    data class WindowSignals(
        val sampleCount: Int,
        val avgSpeedMps: Double?,
        val speedCv: Double?,
        val avgCadenceSpm: Double?,
        val avgVrateMps: Double?,
    )

    /** Per-fix classifier output. */
    data class Classification(
        val state: State,
        val reason: String,
        val signals: WindowSignals,
    )

    /**
     * Tunable thresholds. Starting values from CONTEXT.md item 4 — revise
     * once the first real CSVs land. If you change one, update the
     * corresponding test or the failing test will catch the regression.
     */
    const val WINDOW_SEC: Double = 15.0
    const val ACTIVE_SPEED_FLOOR_MPS: Double = 0.6
    const val ACTIVE_SPEED_CV_CEILING: Double = 0.6
    const val CLAMBERING_CADENCE_FLOOR_SPM: Double = 30.0
    const val CLAMBERING_VRATE_FLOOR_MPS: Double = 0.1

    private const val WINDOW_HALF_MS: Long = (WINDOW_SEC * 500.0).toLong()

    fun classify(samples: List<Sample>): List<Classification> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<Classification>(samples.size)
        for (i in samples.indices) {
            out += classifyAt(samples, i)
        }
        return out
    }

    private fun classifyAt(samples: List<Sample>, i: Int): Classification {
        val center = samples[i].tMs
        // Linear scan rather than a binary search — sample lists per
        // activity are bounded (a few thousand fixes max) and a scan is
        // simpler than maintaining sorted invariants. If activities ever
        // grow large enough to matter, swap for `lowerBound` on tMs.
        var lo = i
        while (lo - 1 >= 0 && center - samples[lo - 1].tMs <= WINDOW_HALF_MS) lo--
        var hi = i
        while (hi + 1 < samples.size && samples[hi + 1].tMs - center <= WINDOW_HALF_MS) hi++

        val window = samples.subList(lo, hi + 1)
        val signals = computeSignals(window)
        val (state, reason) = decide(signals)
        return Classification(state, reason, signals)
    }

    private fun computeSignals(window: List<Sample>): WindowSignals {
        val n = window.size
        if (n == 0) {
            return WindowSignals(0, null, null, null, null)
        }
        val speeds = window.map { it.speedMps }
        val avgSpeed = speeds.average()
        val speedCv = if (n >= 2 && avgSpeed > 0) {
            val mean = avgSpeed
            val variance = speeds.sumOf { (it - mean) * (it - mean) } / (n - 1)
            sqrt(variance) / mean
        } else null

        // Cadence and vertical rate are per-pair signals — undefined when
        // the window has only one fix (no Δ to compute over).
        val cadences = ArrayList<Double>()
        val vrates = ArrayList<Double>()
        for (k in 1 until n) {
            val a = window[k - 1]
            val b = window[k]
            val dtSec = (b.tMs - a.tMs) / 1000.0
            if (dtSec <= 0.0) continue
            val dSteps = (b.cumStepCount - a.cumStepCount).coerceAtLeast(0)
            cadences += dSteps / dtSec * 60.0
            vrates += (b.altM - a.altM) / dtSec
        }
        val avgCadence = if (cadences.isNotEmpty()) cadences.average() else null
        val avgVrate = if (vrates.isNotEmpty()) vrates.average() else null

        return WindowSignals(
            sampleCount = n,
            avgSpeedMps = avgSpeed,
            speedCv = speedCv,
            avgCadenceSpm = avgCadence,
            avgVrateMps = avgVrate,
        )
    }

    private fun decide(s: WindowSignals): Pair<State, String> {
        // Single-fix window short-circuits to DAWDLING — we have no Δt to
        // derive cadence/vrate, and a single speed sample isn't enough to
        // judge stability either. Per CONTEXT.md item 7.
        if (s.sampleCount < 2 || s.avgSpeedMps == null) {
            return State.DAWDLING to "dawdle_no_window"
        }

        val avgSpeed = s.avgSpeedMps
        val cv = s.speedCv ?: Double.POSITIVE_INFINITY
        if (avgSpeed >= ACTIVE_SPEED_FLOOR_MPS && cv <= ACTIVE_SPEED_CV_CEILING) {
            return State.ACTIVE to "active"
        }

        val cadence = s.avgCadenceSpm ?: 0.0
        val vrateAbs = s.avgVrateMps?.let { abs(it) } ?: 0.0
        val cadenceOk = cadence >= CLAMBERING_CADENCE_FLOOR_SPM
        val vrateOk = vrateAbs >= CLAMBERING_VRATE_FLOOR_MPS
        if (cadenceOk || vrateOk) {
            val reason = when {
                cadenceOk && vrateOk -> "clamber_cadence_and_vrate"
                cadenceOk -> "clamber_cadence"
                else -> "clamber_vrate"
            }
            return State.CLAMBERING to reason
        }

        val reason = when {
            avgSpeed < ACTIVE_SPEED_FLOOR_MPS -> "dawdle_low_speed"
            else -> "dawdle_erratic_speed_no_motion"
        }
        return State.DAWDLING to reason
    }
}
