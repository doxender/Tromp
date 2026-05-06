// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps `Sensor.TYPE_ROTATION_VECTOR` as a cold Flow of compass bearings in
 * degrees (0..360, true-screen-azimuth, magnetic-declination not corrected —
 * fine for relative dead-reckoning, not survey-grade).
 *
 * Currently subscribed but unconsumed in v1.x of Quick Start (CONTEXT.md
 * "Quick Start feature spec" → "Out of scope for v1.x"). Wired now so that a
 * later version which back-projects the actual start position from buffered
 * step count + bearings during the deferred-fix gap doesn't need an
 * architectural rework — the source is in place; it just needs a consumer.
 */
class CompassSource(context: Context) {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = sensor != null

    fun readings(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_UI): Flow<Float> = callbackFlow {
        val s = sensor
        if (s == null) { close(); return@callbackFlow }
        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // azimuth is radians from -π..π, with 0 = north, positive east
                val deg = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
                trySend(deg.toFloat())
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, s, samplingPeriodUs)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
