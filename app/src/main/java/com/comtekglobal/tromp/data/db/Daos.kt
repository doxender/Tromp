// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: ActivityEntity)

    @Update
    suspend fun update(activity: ActivityEntity)

    @Query("SELECT * FROM activity WHERE id = :id")
    suspend fun byId(id: Long): ActivityEntity?

    @Query("SELECT * FROM activity ORDER BY startTime DESC")
    fun observeAll(): Flow<List<ActivityEntity>>

    /** Any session with a null endTime — used for crash-recovery on launch. */
    @Query("SELECT * FROM activity WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun findOrphaned(): ActivityEntity?

    @Query(
        """
        SELECT COUNT(*)        AS count,
               SUM(totalDistanceM) AS distanceM,
               SUM(totalAscentM)   AS ascentM,
               SUM(totalDescentM)  AS descentM,
               SUM(movingMs)       AS movingMs
        FROM activity
        WHERE endTime IS NOT NULL
          AND startTime >= :fromMs AND startTime < :toMs
        """
    )
    suspend fun aggregateBetween(fromMs: Long, toMs: Long): StatsRow

    @Query(
        """
        SELECT type AS type,
               COUNT(*)             AS count,
               SUM(totalDistanceM)  AS distanceM,
               SUM(totalAscentM)    AS ascentM,
               SUM(totalDescentM)   AS descentM,
               SUM(movingMs)        AS movingMs
        FROM activity
        WHERE endTime IS NOT NULL
          AND startTime >= :fromMs AND startTime < :toMs
        GROUP BY type
        """
    )
    suspend fun aggregateByTypeBetween(fromMs: Long, toMs: Long): List<TypeStatsRow>

    @Query("DELETE FROM activity WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE activity SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)
}

data class StatsRow(
    val count: Int,
    val distanceM: Double?,
    val ascentM: Double?,
    val descentM: Double?,
    val movingMs: Long?,
)

data class TypeStatsRow(
    val type: String,
    val count: Int,
    val distanceM: Double?,
    val ascentM: Double?,
    val descentM: Double?,
    val movingMs: Long?,
)

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_point WHERE activityId = :activityId ORDER BY seq ASC")
    suspend fun forActivity(activityId: Long): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_point WHERE activityId = :activityId")
    suspend fun countFor(activityId: Long): Int

    @Query("DELETE FROM track_point WHERE activityId = :activityId")
    suspend fun deleteForActivity(activityId: Long)
}

@Dao
interface WaypointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(waypoint: WaypointEntity)

    @Query("SELECT * FROM waypoint WHERE activityId = :activityId ORDER BY seq ASC")
    suspend fun forActivity(activityId: Long): List<WaypointEntity>

    @Query("DELETE FROM waypoint WHERE activityId = :activityId")
    suspend fun deleteForActivity(activityId: Long)
}

@Dao
interface KnownLocationDao {
    @Insert
    suspend fun insert(entry: KnownLocationEntity): Long

    /**
     * All rows whose lat/lon fall within the given bounding box. Callers
     * compute exact Haversine distance to pick the nearest match.
     */
    @Query(
        """
        SELECT * FROM known_location
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        """
    )
    suspend fun withinBox(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
    ): List<KnownLocationEntity>

    @Query("SELECT COUNT(*) FROM known_location")
    suspend fun count(): Int

    /** Bump a row's MRU timestamp — called when a benchmark is reused at START. */
    @Query("UPDATE known_location SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    /**
     * LRU eviction: keep the [keep] most-recently-used rows, delete the rest.
     * Runs after every insert / touch so the cache is bounded at [keep] rows.
     */
    @Query(
        """
        DELETE FROM known_location WHERE id NOT IN (
            SELECT id FROM known_location ORDER BY lastUsedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToMostRecent(keep: Int)

    /** All benchmarks, MRU first — used by the Benchmarks settings screen. */
    @Query("SELECT * FROM known_location ORDER BY lastUsedAt DESC, recordedAt DESC")
    suspend fun allMruDesc(): List<KnownLocationEntity>

    @Query("UPDATE known_location SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("DELETE FROM known_location WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface OfflineRegionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(region: OfflineRegionEntity)

    @Query("SELECT * FROM offline_region ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<OfflineRegionEntity>>

    @Query("DELETE FROM offline_region WHERE id = :id")
    suspend fun deleteById(id: Long)
}
