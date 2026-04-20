// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps FusedLocationProviderClient as a cold Flow. Callers must hold
 * ACCESS_FINE_LOCATION (and ACCESS_BACKGROUND_LOCATION if collecting while
 * backgrounded) before collecting.
 */
class LocationSource(context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 3_000L): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /**
     * One-shot best-effort last-known location. Returns whatever Fused has
     * cached (often already available, no sensor wake needed); null if no fix
     * has been obtained yet or the API call fails.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): Location? = suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }
}
