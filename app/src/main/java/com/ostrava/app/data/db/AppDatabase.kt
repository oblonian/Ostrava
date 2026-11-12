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
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun activityDao(): ActivityDao

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

        const val DATABASE_NAME = "ostrava.db"

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
