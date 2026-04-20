// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
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
    val stepCount: Int = 0,
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

/**
 * Cached orthometric elevation at a known location. Populated every time a
 * benchmark successfully resolves a DEM elevation, so subsequent benchmarks
 * within 50 m can skip the 60 s averaging + network round trip.
 */
@Entity(
    tableName = "known_location",
    indices = [Index("lat"), Index("lon")],
)
data class KnownLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val elevM: Double,
    val source: String,
    val recordedAt: Long,
    val horizAccM: Double?,
    val fixCount: Int?,
)
