// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.tracking

import kotlin.math.abs

/**
 * Accumulates total ascent and total descent from a stream of elevation
 * readings, using a hysteresis threshold to avoid counting GPS/barometer
 * noise as real elevation change.
 *
 * Algorithm:
 *   - Track the current monotonic direction (UP, DOWN, or NONE).
 *   - Maintain an `anchor` elevation (the last confirmed turning point).
 *   - For each new elevation `e`, delta = e - anchor.
 *     If |delta| < threshold: still inside the dead zone; do nothing.
 *     If newDir matches current direction: commit |delta| to the running
 *       total for that direction; advance anchor to e.
 *     If newDir reverses direction: reset anchor to e and flip direction
 *       (the pending dead-zone delta is discarded — it was noise).
 *
 * Threshold: DESIGN.md §6.1 — 3 m default.
 */
class AscentAccumulator(private val thresholdMeters: Double = 3.0) {

    enum class Direction { NONE, UP, DOWN }

    var totalAscentM: Double = 0.0
        private set
    var totalDescentM: Double = 0.0
        private set

    private var anchor: Double? = null
    private var direction: Direction = Direction.NONE

    fun add(elevationM: Double) {
        val a = anchor
        if (a == null) {
            anchor = elevationM
            return
        }
        val delta = elevationM - a
        if (abs(delta) < thresholdMeters) return

        val newDir = if (delta > 0) Direction.UP else Direction.DOWN
        if (newDir == direction || direction == Direction.NONE) {
            if (delta > 0) totalAscentM += delta else totalDescentM += -delta
            anchor = elevationM
            direction = newDir
        } else {
            // Reversal: reset to the new turning point.
            anchor = elevationM
            direction = newDir
        }
    }

    fun reset() {
        totalAscentM = 0.0
        totalDescentM = 0.0
        anchor = null
        direction = Direction.NONE
    }
}
