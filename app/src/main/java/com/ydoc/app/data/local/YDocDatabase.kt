package com.ydoc.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NoteEntity::class,
        SyncTargetEntity::class,
        TombstoneEntity::class,
        AiSuggestionEntity::class,
        ReminderEntryEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class YDocDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun syncTargetDao(): SyncTargetDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun aiSuggestionDao(): AiSuggestionDao
    abstract fun reminderEntryDao(): ReminderEntryDao

    companion object {
        @Volatile
        private var INSTANCE: YDocDatabase? = null

        private val lock = Any()

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

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN remotePath TEXT")
                database.execSQL("ALTER TABLE notes ADD COLUMN lastPulledAt INTEGER")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS tombstones (noteId TEXT NOT NULL, deletedAt INTEGER NOT NULL, PRIMARY KEY(noteId))")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notes ADD COLUMN archivedAt INTEGER")
                database.execSQL("ALTER TABLE notes ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notes ADD COLUMN trashedAt INTEGER")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN audioPublicUri TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_suggestions (
                        id TEXT NOT NULL,
                        noteId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        suggestedTitle TEXT,
                        suggestedCategory TEXT,
                        suggestedPriority TEXT,
                        todoItemsJson TEXT NOT NULL,
                        extractedEntitiesJson TEXT NOT NULL,
                        reminderCandidatesJson TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reminders (
                        id TEXT NOT NULL,
                        noteId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        scheduledAt INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        status TEXT NOT NULL,
                        deliveryTargetsJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN originalContent TEXT")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN tagsJson TEXT")
            }
        }

        fun build(context: Context): YDocDatabase =
            INSTANCE ?: synchronized(lock) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    YDocDatabase::class.java,
                    "ydoc.db",
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    MIGRATION_12_13, MIGRATION_13_14,
                ).build().also { INSTANCE = it }
            }
    }
}
