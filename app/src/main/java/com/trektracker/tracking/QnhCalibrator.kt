// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.tracking

import kotlin.math.pow

/**
 * Inverts the ISA barometric formula to find sea-level pressure (QNH) such that
 * a pressure reading of `avgPressureHpa` at altitude `benchmarkElevM` would
 * imply that sea-level pressure. Use the returned QNH with
 * SensorManager.getAltitude(qnh, currentPressure) for live calibrated altitude.
 *
 * Reference: SensorManager.getAltitude uses h = 44330 * (1 - (p/p0)^(1/5.255)).
 * Solving for p0:  p0 = p / (1 - h/44330)^5.255
 */
fun computeQnhHpa(avgPressureHpa: Double, benchmarkElevM: Double): Double {
    val ratio = 1.0 - benchmarkElevM / 44330.0
    return avgPressureHpa / ratio.pow(5.255)
}
