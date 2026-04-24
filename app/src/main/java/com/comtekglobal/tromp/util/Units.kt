// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.util

const val METERS_PER_FOOT = 0.3048
const val METERS_PER_MILE = 1609.344
const val METERS_PER_KM = 1000.0

fun Double.metersToFeet(): Double = this / METERS_PER_FOOT
fun Double.metersToKm(): Double = this / METERS_PER_KM
fun Double.metersToMiles(): Double = this / METERS_PER_MILE

fun Double.mpsToKmh(): Double = this * 3.6
fun Double.mpsToMph(): Double = this * 3.6 / 1.609344

enum class DistanceUnit { METRIC, IMPERIAL }
enum class ElevationUnit { METERS, FEET }

/** One user-visible setting drives everything else. */
fun DistanceUnit.elevationUnit(): ElevationUnit =
    if (this == DistanceUnit.METRIC) ElevationUnit.METERS else ElevationUnit.FEET

fun formatDistance(meters: Double, unit: DistanceUnit): String = when (unit) {
    DistanceUnit.METRIC -> if (meters < 1000) "%.0f m".format(meters)
                          else "%.2f km".format(meters.metersToKm())
    DistanceUnit.IMPERIAL -> if (meters < METERS_PER_MILE / 10) "%.0f ft".format(meters.metersToFeet())
                             else "%.2f mi".format(meters.metersToMiles())
}

/** Signed elevation/accuracy/length at the meter scale. Precision is caller's choice. */
fun formatElevation(meters: Double, unit: ElevationUnit, decimals: Int = 1): String {
    val v = if (unit == ElevationUnit.METERS) meters else meters.metersToFeet()
    val suffix = if (unit == ElevationUnit.METERS) "m" else "ft"
    return "%.${decimals}f $suffix".format(v)
}

/** Signed delta (always prints +/−). */
fun formatElevationDelta(meters: Double, unit: ElevationUnit, decimals: Int = 1): String {
    val v = if (unit == ElevationUnit.METERS) meters else meters.metersToFeet()
    val suffix = if (unit == ElevationUnit.METERS) "m" else "ft"
    return "%+.${decimals}f $suffix".format(v)
}

fun formatSpeed(mps: Double, unit: DistanceUnit): String = when (unit) {
    DistanceUnit.METRIC -> "%.1f km/h".format(mps.mpsToKmh())
    DistanceUnit.IMPERIAL -> "%.1f mph".format(mps.mpsToMph())
}

/** Pace in min:sec per km or per mile. Returns "—" for zero speed. */
fun formatPace(mps: Double, unit: DistanceUnit): String {
    if (mps <= 0.0) return "—"
    val secondsPerUnit = when (unit) {
        DistanceUnit.METRIC -> METERS_PER_KM / mps
        DistanceUnit.IMPERIAL -> METERS_PER_MILE / mps
    }
    val m = (secondsPerUnit / 60).toInt()
    val s = (secondsPerUnit % 60).toInt()
    val label = if (unit == DistanceUnit.METRIC) "/km" else "/mi"
    return "%d:%02d %s".format(m, s, label)
}
