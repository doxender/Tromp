// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.elevation

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up orthometric elevation for a WGS84 coordinate. USGS 3DEP is tried
 * first; Open-Elevation receives the coordinate only when USGS is unavailable.
 * The entire fallback chain is bounded by one caller-supplied deadline.
 */
object DemClient {

    private const val TAG = "DemClient"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val DEFAULT_LOOKUP_TIMEOUT_MS = 10_000L
    private const val USER_AGENT = "Tromp/1.16.1 (Android; github.com/doxender/Tromp)"
    private const val RETRY_BACKOFF_MS = 750L

    data class Result(
        val usgsElevM: Double?,
        val openElevM: Double?,
        val openElevationAttempted: Boolean,
    ) {
        val best: Double? get() = usgsElevM ?: openElevM
        val source: String? get() = when {
            usgsElevM != null -> "USGS 3DEP"
            openElevM != null -> "Open-Elevation"
            else -> null
        }
    }

    fun lookup(
        lat: Double,
        lon: Double,
        timeoutMs: Long = DEFAULT_LOOKUP_TIMEOUT_MS,
    ): Result {
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        return fallbackLookup(
            usgs = { queryUsgs3dep(lat, lon, deadlineNs) },
            openElevation = { queryOpenElevation(lat, lon, deadlineNs) },
        )
    }

    internal fun fallbackLookup(
        usgs: () -> Double?,
        openElevation: () -> Double?,
    ): Result {
        val usgsElevation = usgs()
        if (usgsElevation != null) {
            return Result(usgsElevation, null, openElevationAttempted = false)
        }
        return Result(null, openElevation(), openElevationAttempted = true)
    }

    fun queryUsgs3dep(lat: Double, lon: Double): Double? {
        val deadlineNs = System.nanoTime() + DEFAULT_LOOKUP_TIMEOUT_MS * 1_000_000L
        return queryUsgs3dep(lat, lon, deadlineNs)
    }

    private fun queryUsgs3dep(lat: Double, lon: Double, deadlineNs: Long): Double? {
        val url = URL(
            "https://epqs.nationalmap.gov/v1/json?" +
                "x=$lon&y=$lat&units=Meters&wkid=4326&includeDate=false"
        )
        return withRetry("USGS 3DEP", deadlineNs) {
            httpGetJson(url, remainingMs(deadlineNs))
                ?.optDouble("value")
                ?.takeIf { !it.isNaN() && it > -1000 }
        }
    }

    fun queryOpenElevation(lat: Double, lon: Double): Double? {
        val deadlineNs = System.nanoTime() + DEFAULT_LOOKUP_TIMEOUT_MS * 1_000_000L
        return queryOpenElevation(lat, lon, deadlineNs)
    }

    private fun queryOpenElevation(
        lat: Double,
        lon: Double,
        deadlineNs: Long,
    ): Double? {
        val url = URL("https://api.open-elevation.com/api/v1/lookup?locations=$lat,$lon")
        return withRetry("Open-Elevation", deadlineNs) {
            httpGetJson(url, remainingMs(deadlineNs))
                ?.optJSONArray("results")
                ?.optJSONObject(0)
                ?.optDouble("elevation")
                ?.takeIf { !it.isNaN() }
        }
    }

    private inline fun withRetry(
        label: String,
        deadlineNs: Long,
        block: () -> Double?,
    ): Double? {
        if (remainingMs(deadlineNs) <= 0) return null
        val first = try {
            block()
        } catch (error: Exception) {
            Log.d(TAG, "$label: first attempt threw ${error.javaClass.simpleName}")
            null
        }
        if (first != null) return first
        if (remainingMs(deadlineNs) <= RETRY_BACKOFF_MS) return null

        try {
            Thread.sleep(RETRY_BACKOFF_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (remainingMs(deadlineNs) <= 0) return null

        return try {
            block().also {
                if (it == null) Log.w(TAG, "$label: both attempts returned null")
            }
        } catch (error: Exception) {
            Log.w(TAG, "$label: retry threw ${error.javaClass.simpleName}")
            null
        }
    }

    private fun httpGetJson(url: URL, remainingMs: Int): JSONObject? {
        if (remainingMs <= 0) return null
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = minOf(CONNECT_TIMEOUT_MS, remainingMs)
        connection.readTimeout = minOf(READ_TIMEOUT_MS, remainingMs)
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return try {
            if (connection.responseCode == 200) {
                JSONObject(connection.inputStream.bufferedReader().readText())
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun remainingMs(deadlineNs: Long): Int =
        ((deadlineNs - System.nanoTime()) / 1_000_000L)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
}
