package com.trektracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ActivityEntity::class,
        TrackPointEntity::class,
        WaypointEntity::class,
        OfflineRegionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TrekDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun trackPoints(): TrackPointDao
    abstract fun waypoints(): WaypointDao
    abstract fun offlineRegions(): OfflineRegionDao

    companion object {
        @Volatile
        private var instance: TrekDatabase? = null

        fun get(context: Context): TrekDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrekDatabase::class.java,
                    "trektracker.db",
                ).build().also { instance = it }
            }
    }
}
