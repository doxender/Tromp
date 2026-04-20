package com.trektracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activity",
    indices = [Index("startTime"), Index("type")],
)
data class ActivityEntity(
    @PrimaryKey val id: Long,           // epoch ms of session start
    val startTime: Long,
    val endTime: Long?,                  // null = in progress / orphaned
    val type: String,                    // hike | run | bike | walk | other
    val name: String?,
    val totalDistanceM: Double,
    val totalAscentM: Double,
    val totalDescentM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val maxGradePct: Double,
    val minGradePct: Double,
    val benchmarkElevM: Double?,
    val qnhHpa: Double?,
)

@Entity(
    tableName = "track_point",
    primaryKeys = ["activityId", "seq"],
    indices = [Index("activityId")],
)
data class TrackPointEntity(
    val activityId: Long,
    val seq: Int,                        // monotonic within the activity
    val time: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,                    // best available (calibrated baro or GPS)
    val gpsAltM: Double,                 // raw GPS, preserved for debug / comparison
    val pressureHpa: Double?,
    val horizAccM: Float,
    val speedMps: Float,
)

@Entity(
    tableName = "waypoint",
    primaryKeys = ["activityId", "seq"],
    indices = [Index("activityId")],
)
data class WaypointEntity(
    val activityId: Long,
    val seq: Int,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val note: String?,
)

@Entity(tableName = "offline_region")
data class OfflineRegionEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val minLat: Double, val maxLat: Double,
    val minLon: Double, val maxLon: Double,
    val minZoom: Int, val maxZoom: Int,
    val tileBytesTotal: Long,
    val downloadedAt: Long,
)
