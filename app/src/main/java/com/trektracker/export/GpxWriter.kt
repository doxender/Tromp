// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.export

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a standard GPX 1.1 file: one `<trk>` containing a single `<trkseg>`
 * with all accepted track points in order, plus `<wpt>` entries for every
 * waypoint. Elevation in meters (WGS84 — maximum compatibility with
 * third-party tools like Strava, Gaia, CalTopo).
 */
object GpxWriter {

    data class TrackPoint(
        val timeEpochMs: Long,
        val lat: Double,
        val lon: Double,
        val elevationM: Double,
    )

    data class Waypoint(
        val timeEpochMs: Long,
        val lat: Double,
        val lon: Double,
        val elevationM: Double,
        val name: String?,
    )

    data class Meta(
        val name: String,
        val type: String,
        val startTimeEpochMs: Long,
    )

    private fun iso8601Utc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun write(
        out: Writer,
        meta: Meta,
        points: List<TrackPoint>,
        waypoints: List<Waypoint>,
    ) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        out.write("\n")
        out.write(
            """<gpx version="1.1" creator="TrekTracker" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
                """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
                """http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        )
        out.write("\n")
        out.write("  <metadata>\n")
        out.write("    <name>${xmlEscape(meta.name)}</name>\n")
        out.write("    <time>${iso8601Utc(meta.startTimeEpochMs)}</time>\n")
        out.write("  </metadata>\n")

        for (w in waypoints) {
            out.write("  <wpt lat=\"${w.lat}\" lon=\"${w.lon}\">\n")
            out.write("    <ele>${"%.2f".format(Locale.US, w.elevationM)}</ele>\n")
            out.write("    <time>${iso8601Utc(w.timeEpochMs)}</time>\n")
            if (!w.name.isNullOrBlank()) {
                out.write("    <name>${xmlEscape(w.name)}</name>\n")
            }
            out.write("  </wpt>\n")
        }

        out.write("  <trk>\n")
        out.write("    <name>${xmlEscape(meta.name)}</name>\n")
        out.write("    <type>${xmlEscape(meta.type)}</type>\n")
        out.write("    <trkseg>\n")
        for (p in points) {
            out.write("      <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">\n")
            out.write("        <ele>${"%.2f".format(Locale.US, p.elevationM)}</ele>\n")
            out.write("        <time>${iso8601Utc(p.timeEpochMs)}</time>\n")
            out.write("      </trkpt>\n")
        }
        out.write("    </trkseg>\n")
        out.write("  </trk>\n")
        out.write("</gpx>\n")
        out.flush()
    }
}
