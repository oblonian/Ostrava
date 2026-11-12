package com.ostrava.app.data.db

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
        SegmentEntity::class,
        SegmentEffortEntity::class,
        BestEffortEntity::class,
        DraftPointEntity::class,
        RecordingDraftEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun activityDao(): ActivityDao

    abstract fun draftDao(): DraftDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN avgHeartRate INTEGER")
                db.execSQL("ALTER TABLE activities ADD COLUMN maxHeartRate INTEGER")
                db.execSQL("ALTER TABLE activities ADD COLUMN feel TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `segments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `activityType` TEXT NOT NULL, " +
                        "`startLat` REAL NOT NULL, `startLon` REAL NOT NULL, " +
                        "`endLat` REAL NOT NULL, `endLon` REAL NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `segment_efforts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`segmentId` INTEGER NOT NULL, `activityId` INTEGER NOT NULL, " +
                        "`durationMillis` INTEGER NOT NULL, `startTime` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`segmentId`) REFERENCES `segments`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_segment_efforts_segmentId` " +
                        "ON `segment_efforts` (`segmentId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_segment_efforts_activityId` " +
                        "ON `segment_efforts` (`activityId`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN effortsVersion INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `best_efforts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`activityId` INTEGER NOT NULL, `activityType` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `distanceMeters` REAL NOT NULL, " +
                        "`durationMillis` INTEGER NOT NULL, `achievedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_best_efforts_activityId` " +
                        "ON `best_efforts` (`activityId`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN heartRate INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `draft_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                        "`altitude` REAL NOT NULL, `time` INTEGER NOT NULL, " +
                        "`speedMps` REAL NOT NULL, `segment` INTEGER NOT NULL, `heartRate` INTEGER)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recording_draft` (" +
                        "`id` INTEGER NOT NULL, `type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, " +
                        "`movingTimeMillis` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, " +
                        "`elevationGainMeters` REAL NOT NULL, `maxSpeedMps` REAL NOT NULL, " +
                        "`segment` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        const val DATABASE_NAME = "ostrava.db"

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
