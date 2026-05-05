// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.export

import com.comtekglobal.tromp.data.db.ActivityEntity
import com.comtekglobal.tromp.data.db.TrackPointEntity
import com.comtekglobal.tromp.util.haversineMeters
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Writes the enriched per-point capture (track_point columns plus computed
 * neighbor deltas) as a flat CSV. Intended as a diagnostic — the goal is to
 * pull a recorded activity into Excel and see what hiking vs. puttering vs.
 * still-stops actually look like across stride length, sinuosity, speed,
 * elevation change, etc., so the eventual TrackPostProcessor classifier can
 * be tuned against real data instead of synthetic intuitions.
 *
 * The deltas (distance, time, bearing change, step delta) are computed at
 * export time rather than stored — they're cheap to compute and keeping them
 * out of the schema means migrations stay simple.
 */
object CsvWriter {

    private fun iso8601Utc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    private fun localTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }

    /** Smallest absolute angular delta in [-180, 180] degrees. */
    private fun bearingDelta(prev: Float?, curr: Float?): Float? {
        if (prev == null || curr == null) return null
        var d = curr - prev
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return abs(d)
    }

    fun write(out: Writer, activity: ActivityEntity, points: List<TrackPointEntity>) {
        // Header banner — a few activity-summary rows above the table so the
        // CSV is self-describing when opened cold in Excel.
        out.write("# Tromp activity export\n")
        out.write("# id,${activity.id}\n")
        out.write("# name,${escape(activity.name ?: "")}\n")
        out.write("# type,${escape(activity.type)}\n")
        out.write("# start_utc,${iso8601Utc(activity.startTime)}\n")
        out.write("# end_utc,${activity.endTime?.let { iso8601Utc(it) } ?: ""}\n")
        out.write("# elapsed_ms,${activity.elapsedMs}\n")
        out.write("# moving_ms,${activity.movingMs}\n")
        out.write("# total_distance_m,%.3f\n".format(Locale.US, activity.totalDistanceM))
        out.write("# total_ascent_m,%.3f\n".format(Locale.US, activity.totalAscentM))
        out.write("# total_descent_m,%.3f\n".format(Locale.US, activity.totalDescentM))
        out.write("# avg_speed_mps,%.3f\n".format(Locale.US, activity.avgSpeedMps))
        out.write("# max_speed_mps,%.3f\n".format(Locale.US, activity.maxSpeedMps))
        out.write("# max_grade_pct,%.2f\n".format(Locale.US, activity.maxGradePct))
        out.write("# min_grade_pct,%.2f\n".format(Locale.US, activity.minGradePct))
        out.write("# step_count,${activity.stepCount}\n")
        out.write("# benchmark_elev_m,${activity.benchmarkElevM ?: ""}\n")
        out.write("# qnh_hpa,${activity.qnhHpa ?: ""}\n")
        out.write("#\n")

        // Column headers.
        out.write(
            listOf(
                "seq", "time_utc", "time_local", "tMs",
                "lat", "lon", "alt_m", "gps_alt_m",
                "pressure_hpa", "horiz_acc_m",
                "speed_mps", "bearing_deg",
                "step_count", "is_auto_paused",
                "dist_from_prev_m", "dt_from_prev_s",
                "bearing_change_deg", "steps_delta",
                "stride_m_per_step", "cadence_spm",
                "vertical_rate_mps",
            ).joinToString(",")
        )
        out.write("\n")

        var prev: TrackPointEntity? = null
        for (p in points) {
            val dist = prev?.let { haversineMeters(it.lat, it.lon, p.lat, p.lon) }
            val dtSec = prev?.let { (p.time - it.time) / 1000.0 }
            val dBear = bearingDelta(prev?.bearingDeg, p.bearingDeg)
            val dSteps = prev?.let { p.cumStepCount - it.cumStepCount }
            val stride = if (dSteps != null && dSteps > 0 && dist != null && dist > 0) {
                dist / dSteps
            } else null
            // cadence_spm = steps_delta / dt * 60. Only meaningful when dt > 0
            // and we have a previous fix to diff against. While auto-paused the
            // step counter doesn't advance so cadence naturally evaluates to 0,
            // which is the right answer.
            val cadenceSpm = if (dSteps != null && dtSec != null && dtSec > 0) {
                dSteps / dtSec * 60.0
            } else null
            // vertical_rate_mps = Δalt / dt. Sign carries direction (positive =
            // climbing, negative = descending). altM is the chosen altitude
            // (baro-when-calibrated, GPS otherwise) so this matches what the
            // ascent accumulator sees.
            val vrate = if (dtSec != null && dtSec > 0) {
                (p.altM - (prev?.altM ?: p.altM)) / dtSec
            } else null

            out.write(
                listOf(
                    p.seq.toString(),
                    iso8601Utc(p.time),
                    localTime(p.time),
                    p.time.toString(),
                    "%.7f".format(Locale.US, p.lat),
                    "%.7f".format(Locale.US, p.lon),
                    "%.3f".format(Locale.US, p.altM),
                    "%.3f".format(Locale.US, p.gpsAltM),
                    p.pressureHpa?.let { "%.2f".format(Locale.US, it) } ?: "",
                    "%.2f".format(Locale.US, p.horizAccM),
                    "%.3f".format(Locale.US, p.speedMps),
                    p.bearingDeg?.let { "%.1f".format(Locale.US, it) } ?: "",
                    p.cumStepCount.toString(),
                    if (p.isAutoPaused) "1" else "0",
                    dist?.let { "%.3f".format(Locale.US, it) } ?: "",
                    dtSec?.let { "%.3f".format(Locale.US, it) } ?: "",
                    dBear?.let { "%.1f".format(Locale.US, it) } ?: "",
                    dSteps?.toString() ?: "",
                    stride?.let { "%.3f".format(Locale.US, it) } ?: "",
                    cadenceSpm?.let { "%.1f".format(Locale.US, it) } ?: "",
                    vrate?.let { "%.3f".format(Locale.US, it) } ?: "",
                ).joinToString(",")
            )
            out.write("\n")
            prev = p
        }
        out.flush()
    }

    /** Minimal CSV escape: wrap in quotes if the field contains , " or newline. */
    private fun escape(s: String): String {
        if (s.indexOfAny(charArrayOf(',', '"', '\n', '\r')) < 0) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
}
