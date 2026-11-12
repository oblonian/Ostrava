package com.ostrava.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.AppDatabase
import com.ostrava.app.domain.TrackPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain Application so tests don't run OstravaApp's startup work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Builds a database exactly as app version 1.0 created it, then opens it with the
     * current schema. Room validates the migrated schema on open and throws if any
     * migration statement is off, so this guards real users' data across upgrades.
     */
    @Test
    fun `version 1 database migrates to the current schema and keeps its data`() {
        val name = "migration-test.db"
        context.deleteDatabase(name)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `activities` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`type` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                                "`startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, " +
                                "`movingTimeMillis` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, " +
                                "`avgSpeedMps` REAL NOT NULL, `maxSpeedMps` REAL NOT NULL, " +
                                "`elevationGainMeters` REAL NOT NULL, `calories` INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `track_points` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`activityId` INTEGER NOT NULL, `latitude` REAL NOT NULL, " +
                                "`longitude` REAL NOT NULL, `altitude` REAL NOT NULL, " +
                                "`time` INTEGER NOT NULL, `speedMps` REAL NOT NULL, " +
                                "`segment` INTEGER NOT NULL, " +
                                "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) " +
                                "ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_track_points_activityId` " +
                                "ON `track_points` (`activityId`)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO activities (id, type, title, startTime, endTime, movingTimeMillis, " +
                    "distanceMeters, avgSpeedMps, maxSpeedMps, elevationGainMeters, calories) " +
                    "VALUES (1, 'RUN', 'Morning Run', 1000, 2000, 900000, 3000.0, 3.3, 4.0, 12.0, 210)"
            )
            execSQL(
                "INSERT INTO track_points (activityId, latitude, longitude, altitude, time, speedMps, segment) " +
                    "VALUES (1, 50.0, 14.0, 200.0, 1000, 3.0, 0)"
            )
        }
        helper.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val activities = db.activityDao().getActivitiesList()
                assertEquals(1, activities.size)
                assertEquals("Morning Run", activities[0].title)
                assertNull(activities[0].avgHeartRate)
                assertNull(activities[0].effortsVersion)
                assertEquals(1, db.activityDao().getTrackPoints(1).size)
                // Tables added by the migrations are queryable.
                assertTrue(db.activityDao().getSegmentsList().isEmpty())
                assertTrue(db.activityDao().observeAllBestEfforts().first().isEmpty())
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `best efforts are cached on save and ranked across activities`() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = ActivityRepository(db.activityDao())
            runBlocking {
                val slowId = repository.saveActivityWithEfforts(
                    activity(startTime = 1_000_000L), straightTrack(1_000_000L, metersPerSecond = 2.5)
                )
                val fastId = repository.saveActivityWithEfforts(
                    activity(startTime = 9_000_000L), straightTrack(9_000_000L, metersPerSecond = 3.5)
                )

                val slow = repository.observeRankedEfforts(slowId).first()
                val fast = repository.observeRankedEfforts(fastId).first()

                // A 2.2 km run covers the 400 m, 1 km and 1 mile benchmarks but not 5 km.
                assertEquals(listOf("400 m", "1 km", "1 mile"), fast.map { it.label })
                assertTrue(fast.all { it.effortRank == 1 })
                assertTrue(slow.all { it.effortRank == 2 })

                val oneKm = fast.first { it.label == "1 km" }
                // 1 km at 3.5 m/s is ~286 s; allow for point spacing.
                assertTrue(oneKm.durationMillis in 280_000L..292_000L)

                // Deleting the record holder promotes the remaining effort.
                repository.deleteActivity(fastId)
                assertTrue(repository.observeRankedEfforts(slowId).first().all { it.effortRank == 1 })
            }
        } finally {
            db.close()
        }
    }

    private fun activity(startTime: Long) = ActivityEntity(
        type = "RUN",
        title = "Test run",
        startTime = startTime,
        endTime = startTime + 900_000L,
        movingTimeMillis = 900_000L,
        distanceMeters = 2200.0,
        avgSpeedMps = 2.5,
        maxSpeedMps = 4.0,
        elevationGainMeters = 0.0,
        calories = 100,
    )

    /** 2.2 km due north at a constant speed, one point every two seconds. */
    private fun straightTrack(startTime: Long, metersPerSecond: Double): List<TrackPoint> {
        val metersPerDegree = 111_320.0
        val stepSeconds = 2
        val steps = (2200.0 / (metersPerSecond * stepSeconds)).toInt() + 1
        return (0..steps).map { i ->
            TrackPoint(
                latitude = 50.0 + (i * metersPerSecond * stepSeconds) / metersPerDegree,
                longitude = 14.0,
                altitude = 200.0,
                timeMillis = startTime + i * stepSeconds * 1000L,
                speedMps = metersPerSecond.toFloat(),
                segment = 0,
            )
        }
    }
}
