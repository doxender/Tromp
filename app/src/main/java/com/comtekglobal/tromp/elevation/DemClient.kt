// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.elevation

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up orthometric elevation from a DEM for a WGS84 lat/lon.
 * Prefers USGS 3DEP in the continental US (lidar-derived, ~0.1–1 m accurate),
 * falls back to Open-Elevation SRTM globally (~5–10 m accurate).
 *
 * Callers should run these on a background dispatcher; both methods make
 * blocking HTTP requests with short timeouts. Both endpoints are known to
 * return transient 5xx / timeouts (USGS EPQS in particular), so each lookup
 * retries once with a short backoff before giving up. The reason for the
 * final failure is logged at WARN level.
 */
object DemClient {

    private const val TAG = "DemClient"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val USER_AGENT = "Tromp/0.1 (Android; github.com/doxender/Tromp)"
    private const val RETRY_BACKOFF_MS = 750L

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

    fun queryUsgs3dep(lat: Double, lon: Double): Double? {
        val url = URL(
            "https://epqs.nationalmap.gov/v1/json?" +
                "x=$lon&y=$lat&units=Meters&wkid=4326&includeDate=false"
        )
        return withRetry("USGS 3DEP") {
            httpGetJson(url)?.optDouble("value")?.takeIf { !it.isNaN() && it > -1000 }
        }
    }

    fun queryOpenElevation(lat: Double, lon: Double): Double? {
        val url = URL("https://api.open-elevation.com/api/v1/lookup?locations=$lat,$lon")
        return withRetry("Open-Elevation") {
            httpGetJson(url)
                ?.optJSONArray("results")
                ?.optJSONObject(0)
                ?.optDouble("elevation")
                ?.takeIf { !it.isNaN() }
        }
    }

    /**
     * Runs `block` up to twice, returning the first non-null result. Logs the
     * first failure at DEBUG and a final failure at WARN so intermittent
     * outages are visible in logcat without spamming on success.
     */
    private inline fun withRetry(label: String, block: () -> Double?): Double? {
        val first = try { block() } catch (t: Throwable) {
            Log.d(TAG, "$label: first attempt threw ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (first != null) return first
        Log.d(TAG, "$label: first attempt returned null, retrying after ${RETRY_BACKOFF_MS}ms")
        try { Thread.sleep(RETRY_BACKOFF_MS) } catch (_: InterruptedException) { /* ignore */ }
        val second = try { block() } catch (t: Throwable) {
            Log.w(TAG, "$label: retry threw ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        if (second == null) Log.w(TAG, "$label: both attempts returned null")
        return second
    }

    private fun httpGetJson(url: URL): JSONObject? {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            val code = c.responseCode
            if (code == 200) {
                JSONObject(c.inputStream.bufferedReader().readText())
            } else {
                Log.d(TAG, "$url → HTTP $code")
                null
            }
        } finally { c.disconnect() }
    }
}
