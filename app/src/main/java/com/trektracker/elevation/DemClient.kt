// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.elevation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up orthometric elevation from a DEM for a WGS84 lat/lon.
 * Prefers USGS 3DEP in the continental US (lidar-derived, ~0.1–1 m accurate),
 * falls back to Open-Elevation SRTM globally (~5–10 m accurate).
 *
 * Callers should run these on a background dispatcher; both methods make
 * blocking HTTP requests with short timeouts.
 */
object DemClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val USER_AGENT = "TrekTracker/0.1 (Android; github.com/trektracker)"

    data class Result(
        val usgsElevM: Double?,
        val openElevM: Double?,
    ) {
        /** Recommended benchmark: USGS if available, else Open-Elevation, else null. */
        val best: Double? get() = usgsElevM ?: openElevM
        val source: String? get() = when {
            usgsElevM != null -> "USGS 3DEP"
            openElevM != null -> "Open-Elevation"
            else -> null
        }
    }

    fun lookup(lat: Double, lon: Double): Result =
        Result(queryUsgs3dep(lat, lon), queryOpenElevation(lat, lon))

    fun queryUsgs3dep(lat: Double, lon: Double): Double? = try {
        val url = URL(
            "https://epqs.nationalmap.gov/v1/json?" +
                "x=$lon&y=$lat&units=Meters&wkid=4326&includeDate=false"
        )
        httpGetJson(url)?.optDouble("value")?.takeIf { !it.isNaN() && it > -1000 }
    } catch (_: Exception) { null }

    fun queryOpenElevation(lat: Double, lon: Double): Double? = try {
        val url = URL("https://api.open-elevation.com/api/v1/lookup?locations=$lat,$lon")
        httpGetJson(url)
            ?.optJSONArray("results")
            ?.optJSONObject(0)
            ?.optDouble("elevation")
            ?.takeIf { !it.isNaN() }
    } catch (_: Exception) { null }

    private fun httpGetJson(url: URL): JSONObject? {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
        } finally { c.disconnect() }
    }
}
