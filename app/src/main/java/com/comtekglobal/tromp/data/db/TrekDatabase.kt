// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Daniel V. Oxender. See LICENSE for terms.
// This notice must be preserved in all derivative works.
package com.comtekglobal.tromp.data.db

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
        KnownLocationEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TrekDatabase : RoomDatabase() {
    abstract fun activities(): ActivityDao
    abstract fun trackPoints(): TrackPointDao
    abstract fun waypoints(): WaypointDao
    abstract fun offlineRegions(): OfflineRegionDao
    abstract fun knownLocations(): KnownLocationDao

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

        /** v2 → v3: adds the `known_location` cache table. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS known_location (
                        id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        lat         REAL NOT NULL,
                        lon         REAL NOT NULL,
                        elevM       REAL NOT NULL,
                        source      TEXT NOT NULL,
                        recordedAt  INTEGER NOT NULL,
                        horizAccM   REAL,
                        fixCount    INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_known_location_lat ON known_location(lat)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_known_location_lon ON known_location(lon)")
            }
        }

        /** v3 → v4: adds `lastUsedAt` to `known_location` for LRU eviction. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE known_location ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0"
                )
                // Seed with recordedAt so pre-migration rows have a sensible MRU order.
                db.execSQL("UPDATE known_location SET lastUsedAt = recordedAt")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_known_location_lastUsedAt ON known_location(lastUsedAt)"
                )
            }
        }

        /** v4 → v5: adds nullable `name` column for user-assigned benchmark labels. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE known_location ADD COLUMN name TEXT")
            }
        }

        fun get(context: Context): TrekDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrekDatabase::class.java,
                    "trektracker.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
