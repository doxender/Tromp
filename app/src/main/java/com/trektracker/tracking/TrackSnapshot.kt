package com.trektracker.tracking

/** Point-in-time view of the live tracking state, emitted to the UI. */
data class TrackSnapshot(
    val activityId: Long,
    val type: String,
    val isPaused: Boolean,
    val isAutoPaused: Boolean,

    val lat: Double?,
    val lon: Double?,
    val elevationM: Double?,
    val horizontalAccuracyM: Float?,
    val speedMps: Double,

    val totalDistanceM: Double,
    val totalAscentM: Double,
    val totalDescentM: Double,

    val currentGradePct: Double?,
    val maxGradePct: Double,
    val minGradePct: Double,

    val avgSpeedMps: Double,
    val maxSpeedMps: Double,

    val elapsedMs: Long,
    val movingMs: Long,

    val pressureHpa: Double?,
    val qnhHpa: Double?,
) {
    companion object {
        fun empty(activityId: Long, type: String) = TrackSnapshot(
            activityId = activityId,
            type = type,
            isPaused = false,
            isAutoPaused = false,
            lat = null, lon = null, elevationM = null,
            horizontalAccuracyM = null, speedMps = 0.0,
            totalDistanceM = 0.0, totalAscentM = 0.0, totalDescentM = 0.0,
            currentGradePct = null, maxGradePct = Double.NEGATIVE_INFINITY,
            minGradePct = Double.POSITIVE_INFINITY,
            avgSpeedMps = 0.0, maxSpeedMps = 0.0,
            elapsedMs = 0L, movingMs = 0L,
            pressureHpa = null, qnhHpa = null,
        )
    }
}
