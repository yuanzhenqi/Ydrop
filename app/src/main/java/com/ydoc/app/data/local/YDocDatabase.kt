package com.ydoc.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, SyncTargetEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class YDocDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun syncTargetDao(): SyncTargetDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN category TEXT NOT NULL DEFAULT 'NOTE'")
                database.execSQL("ALTER TABLE notes ADD COLUMN priority TEXT NOT NULL DEFAULT 'MEDIUM'")
                database.execSQL("ALTER TABLE notes ADD COLUMN colorToken TEXT NOT NULL DEFAULT 'SAGE'")
                database.execSQL("ALTER TABLE notes ADD COLUMN audioPath TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN syncError TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN relayFileId TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN relayUrl TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN relayExpiresAt INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN transcript TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN transcriptionStatus TEXT NOT NULL DEFAULT 'NOT_STARTED'")
                database.execSQL("ALTER TABLE notes ADD COLUMN transcriptionError TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN transcriptionRequestId TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN audioFormat TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN transcriptionUpdatedAt INTEGER")
            }
        }

        fun build(context: Context): YDocDatabase =
            Room.databaseBuilder(
                context,
                YDocDatabase::class.java,
                "ydoc.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
