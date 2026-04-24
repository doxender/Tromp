// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import com.comtekglobal.tromp.util.haversineMeters

/**
 * Watches a stream of accepted GPS fixes during a tracking session and
 * suggests when the activity is probably over. Two signals:
 *
 *  - **Speed spike** — three consecutive fixes whose speed is ≥ 3× the
 *    trailing 60 s mean AND ≥ [minSpikeSpeedMps] (default 4.47 m/s,
 *    ~10 mph). Catches "got into a vehicle".
 *
 *  - **Returned home** — session has been running ≥ [minDurationMs]
 *    (default 10 min), current speed ≤ [stoppedSpeedMps] (default 1 m/s,
 *    ~2.2 mph), and current position is within [homeRadiusM] of the first
 *    fix recorded. Catches "walked a loop, back at the house".
 *
 * When a signal fires, [feed] returns a non-null [Signal] with the
 * timestamp to trim after (everything strictly after that time is
 * considered trailing noise and should be discarded). The detector then
 * suppresses re-fires of the same trigger until the condition clears and
 * re-arms, so dismissing a false positive and continuing doesn't spam.
 *
 * All logic is pure — no Android framework deps. State is internal and
 * reset on [reset] or when re-creating the detector.
 */
class AutoStopDetector(
    private val minSpikeSpeedMps: Double = 4.47,         // 10 mph
    private val spikeMultiplier: Double = 3.0,
    private val spikeConsecutiveFixes: Int = 3,
    private val trailingMeanWindowMs: Long = 60_000L,
    private val stoppedSpeedMps: Double = 1.0,            // ~2.2 mph
    /**
     * Minimum session duration before returned-home can fire. Low by design:
     * the `leftHomeOnce` guard already rejects the pacing-in-the-yard
     * false-positive (user never got > [homeRadiusM] from the start).
     * A trivial floor still avoids an instant fire if the very first fix
     * lands inside the home radius.
     */
    private val minDurationMs: Long = 0L,
    private val homeRadiusM: Double = 100.0,
) {

    enum class Reason { SPEED_SPIKE, RETURNED_HOME }

    /** A stop suggestion. [trimAfterMs] is the last timestamp to keep; everything after is dropped. */
    data class Signal(val reason: Reason, val trimAfterMs: Long)

    /** One observed fix — minimum shape the detector needs. */
    data class Sample(val tMs: Long, val lat: Double, val lon: Double, val speedMps: Double)

    /** Internal state snapshot for debugging. Updated after every [feed]. */
    data class Telemetry(
        val trailingMeanMps: Double,
        val consecutiveSpikes: Int,
        val spikeArmed: Boolean,
        val leftHomeOnce: Boolean,
        val homeArmed: Boolean,
        val distFromStartM: Double,
        val durationMs: Long,
    )

    @Volatile
    var telemetry: Telemetry = Telemetry(0.0, 0, true, false, false, 0.0, 0L)
        private set

    private val window: ArrayDeque<Sample> = ArrayDeque()
    private var startSample: Sample? = null
    private var consecutiveSpikes: Int = 0
    private var firstSpikeTs: Long? = null
    private var spikeArmed: Boolean = true
    private var homeArmed: Boolean = false
    private var leftHomeOnce: Boolean = false

    fun reset() {
        window.clear()
        startSample = null
        consecutiveSpikes = 0
        firstSpikeTs = null
        spikeArmed = true
        homeArmed = false
        leftHomeOnce = false
        telemetry = Telemetry(0.0, 0, true, false, false, 0.0, 0L)
    }

    /** Returns a [Signal] on the fix that first triggered a rule, else null. */
    fun feed(sample: Sample): Signal? {
        if (startSample == null) startSample = sample
        val start = startSample!!

        window.addLast(sample)
        while (window.isNotEmpty() &&
            sample.tMs - window.first().tMs > trailingMeanWindowMs) {
            window.removeFirst()
        }

        // --- Speed spike ---
        val trailing = if (window.size >= 2) {
            window.take(window.size - 1).map { it.speedMps }.average()
        } else 0.0
        val spiking = sample.speedMps >= minSpikeSpeedMps &&
            (trailing == 0.0 || sample.speedMps >= trailing * spikeMultiplier)
        if (spiking) {
            if (consecutiveSpikes == 0) firstSpikeTs = sample.tMs
            consecutiveSpikes++
        } else {
            // Condition cleared — re-arm and reset the counter.
            if (!spikeArmed) spikeArmed = true
            consecutiveSpikes = 0
            firstSpikeTs = null
        }
        var signal: Signal? = null
        if (spikeArmed && consecutiveSpikes >= spikeConsecutiveFixes) {
            spikeArmed = false
            val trim = firstSpikeTs ?: sample.tMs
            // Trim to the fix immediately before the spike started.
            val lastPreSpike = window.lastOrNull { it.tMs < trim }?.tMs ?: start.tMs
            signal = Signal(Reason.SPEED_SPIKE, lastPreSpike)
        }

        // --- Returned home ---
        val distFromStart = haversineMeters(start.lat, start.lon, sample.lat, sample.lon)
        val durationMs = sample.tMs - start.tMs
        if (!leftHomeOnce && distFromStart > homeRadiusM) {
            leftHomeOnce = true
            homeArmed = true
        }
        if (signal == null && homeArmed && leftHomeOnce &&
            durationMs >= minDurationMs &&
            sample.speedMps <= stoppedSpeedMps &&
            distFromStart <= homeRadiusM
        ) {
            homeArmed = false
            signal = Signal(Reason.RETURNED_HOME, sample.tMs)
        }
        // Re-arm returned-home if the user leaves the radius again.
        if (!homeArmed && leftHomeOnce && distFromStart > homeRadiusM) {
            homeArmed = true
        }

        telemetry = Telemetry(
            trailingMeanMps = trailing,
            consecutiveSpikes = consecutiveSpikes,
            spikeArmed = spikeArmed,
            leftHomeOnce = leftHomeOnce,
            homeArmed = homeArmed,
            distFromStartM = distFromStart,
            durationMs = durationMs,
        )
        return signal
    }
}
