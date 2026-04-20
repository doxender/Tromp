// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.export

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class GpxWriterTest {

    @Test fun emits_well_formed_gpx_11_skeleton() {
        val out = StringWriter()
        GpxWriter.write(
            out,
            meta = GpxWriter.Meta(name = "Hike · 2026-04-19 14:32", type = "hike", startTimeEpochMs = 0L),
            points = listOf(
                GpxWriter.TrackPoint(0L, 37.0, -122.0, 100.0),
                GpxWriter.TrackPoint(1000L, 37.0001, -122.0001, 101.5),
            ),
            waypoints = listOf(
                GpxWriter.Waypoint(500L, 37.00005, -122.00005, 100.7, "Scenic overlook")
            ),
        )
        val xml = out.toString()
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("""version="1.1""""))
        assertTrue(xml.contains("<trk>"))
        assertTrue(xml.contains("<trkseg>"))
        assertTrue(xml.contains("<trkpt lat=\"37.0\" lon=\"-122.0\">"))
        assertTrue(xml.contains("<wpt lat=\"37.00005\" lon=\"-122.00005\">"))
        assertTrue(xml.contains("<name>Scenic overlook</name>"))
        assertTrue(xml.contains("</gpx>"))
    }

    @Test fun xml_escapes_dangerous_characters_in_names() {
        val out = StringWriter()
        GpxWriter.write(
            out,
            meta = GpxWriter.Meta(name = "Fred & <son>", type = "hike", startTimeEpochMs = 0L),
            points = emptyList(),
            waypoints = emptyList(),
        )
        val xml = out.toString()
        assertTrue(xml.contains("Fred &amp; &lt;son&gt;"))
    }
}
