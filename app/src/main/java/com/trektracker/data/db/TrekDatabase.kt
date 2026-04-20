// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.trektracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActivityEntity::class,
        TrackPointEntity::class,
        WaypointEntity::class,
        OfflineRegionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TrekDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun trackPoints(): TrackPointDao
    abstract fun waypoints(): WaypointDao
    abstract fun offlineRegions(): OfflineRegionDao

    companion object {
        @Volatile
        private var instance: TrekDatabase? = null

        /** v1 → v2: adds stepCount column to `activity` (default 0). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE activity ADD COLUMN stepCount INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): TrekDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrekDatabase::class.java,
                    "trektracker.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
