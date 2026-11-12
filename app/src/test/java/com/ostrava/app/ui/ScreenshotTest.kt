package com.ostrava.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.size
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.EffortForActivity
import com.ostrava.app.data.db.RankedEffort
import com.ostrava.app.data.db.SegmentWithStats
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.Feel
import com.ostrava.app.domain.Insight
import com.ostrava.app.domain.InsightAction
import com.ostrava.app.domain.IntervalPhase
import com.ostrava.app.domain.Split
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.tracking.RecordingState
import com.ostrava.app.tracking.TrackingStatus
import com.ostrava.app.ui.components.RouteThumbnail
import com.ostrava.app.ui.detail.DetailActions
import com.ostrava.app.ui.detail.DetailContent
import com.ostrava.app.ui.detail.DetailUiState
import com.ostrava.app.ui.feed.FeedContent
import com.ostrava.app.ui.feed.FeedUiState
import com.ostrava.app.ui.feed.WeeklySummary
import com.ostrava.app.ui.onboarding.OnboardingContent
import com.ostrava.app.ui.profile.ProfileActions
import com.ostrava.app.ui.profile.ProfileContent
import com.ostrava.app.ui.record.RecordActions
import com.ostrava.app.ui.record.RecordContent
import com.ostrava.app.ui.stats.ChartBucket
import com.ostrava.app.ui.stats.EffortRecord
import com.ostrava.app.ui.stats.MilestoneRecord
import com.ostrava.app.ui.stats.RecordsUiState
import com.ostrava.app.ui.stats.StatsActions
import com.ostrava.app.ui.stats.StatsContent
import com.ostrava.app.ui.stats.StatsUiState
import com.ostrava.app.ui.stats.TrainingLoad
import com.ostrava.app.ui.theme.OstravaTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders every main screen with sample data and writes the images to build/screenshots,
 * so the design can be reviewed from CI output without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    /** Thursday 12 November 2026, 07:40 local time. */
    private val now = ZonedDateTime.of(2026, 11, 12, 7, 40, 0, 0, ZoneId.systemDefault())
    private val nowMillis = now.toInstant().toEpochMilli()

    private fun daysAgo(days: Long, hour: Int = 7) =
        now.minusDays(days).withHour(hour).withMinute(12).toInstant().toEpochMilli()

    private val settings = UserSettings(userName = "Alex", weeklyGoalKm = 25f, lastBackupAt = daysAgo(12))

    private fun shoot(name: String, heightDp: Int = 915, dark: Boolean = false, content: @Composable () -> Unit) {
        captureRoboImage(
            filePath = "build/screenshots/$name.png",
            roborazziComposeOptions = RoborazziComposeOptions { size(widthDp = 412, heightDp = heightDp) },
        ) {
            OstravaTheme(darkTheme = dark) { content() }
        }
    }

    // ---- Sample data ----

    private fun loop(scale: Double = 1.0, wobble: Double = 3.0): List<TrackPoint> = (0..360 step 4).map { degrees ->
        val t = Math.toRadians(degrees.toDouble())
        TrackPoint(
            latitude = 50.08 + scale * (0.008 * sin(t) + 0.0025 * sin(wobble * t)),
            longitude = 14.42 + scale * (0.014 * cos(t) + 0.004 * cos(2 * t)),
            altitude = 200.0 + 30 * sin(2 * t),
            timeMillis = nowMillis - 3_600_000 + degrees * 8_000L,
            speedMps = (3.2 + 0.5 * sin(5 * t)).toFloat(),
            segment = 0,
            heartRate = (148 + 22 * sin(1.5 * t)).toInt(),
        )
    }

    private fun activity(
        id: Long,
        type: ActivityType,
        title: String,
        start: Long,
        km: Double,
        minutes: Long,
        climb: Double,
        feel: String? = null,
        heartRate: Int? = null,
    ) = ActivityEntity(
        id = id,
        type = type.name,
        title = title,
        startTime = start,
        endTime = start + minutes * 60_000,
        movingTimeMillis = minutes * 60_000,
        distanceMeters = km * 1000,
        avgSpeedMps = km * 1000 / (minutes * 60.0),
        maxSpeedMps = km * 1000 / (minutes * 60.0) * 1.4,
        elevationGainMeters = climb,
        calories = (km * 68).toInt(),
        avgHeartRate = heartRate,
        maxHeartRate = heartRate?.plus(24),
        feel = feel,
    )

    private val activities = listOf(
        activity(1, ActivityType.RUN, "Morning Run", daysAgo(0, 6), 8.42, 43, 64.0, Feel.MODERATE, 152),
        activity(2, ActivityType.RIDE, "Evening Ride", daysAgo(1, 18), 32.6, 82, 310.0, Feel.EASY),
        activity(3, ActivityType.RUN, "Intervals by the river", daysAgo(3, 7), 6.1, 31, 22.0, Feel.HARD, 164),
        activity(4, ActivityType.HIKE, "Ridge loop", daysAgo(5, 9), 11.8, 204, 720.0),
        activity(5, ActivityType.WALK, "Lunch walk", daysAgo(6, 12), 3.2, 38, 12.0),
    )

    private val tracks = mapOf(
        1L to loop(), 2L to loop(2.4, 5.0), 3L to loop(0.7, 2.0), 4L to loop(1.3, 4.0), 5L to loop(0.4, 1.0),
    )

    private val feedState = FeedUiState(
        activities = activities,
        settings = settings,
        weeklySummary = WeeklySummary(47_120.0, 156 * 60_000L, 3, 396.0),
        recordCounts = mapOf(1L to 2),
        insights = listOf(
            Insight(
                "goal-close", "🎯", "6.5 km to go this week",
                "You're at 18.5 km of 25.0 km. One more outing before Sunday gets you there.",
                80, InsightAction.RECORD,
            ),
            Insight(
                "tip-1", "🚩", "Race your own routes",
                "Open any activity and choose Segment. Every later activity over that stretch gets timed against your best.",
                10,
            ),
        ),
        streakWeeks = 6,
        tracks = tracks,
        loading = false,
    ).let { it.copy(weeklySummary = it.weeklySummary.copy(distanceMeters = 18_500.0)) }

    @Composable
    private fun FakeMap(modifier: Modifier, points: List<TrackPoint>) {
        Box(modifier.background(Color(0xFFE4E9DD))) {
            RouteThumbnail(
                points = points,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                color = Color(0xFFFC5200),
            )
        }
    }

    // ---- Screens ----

    @Test
    fun feed() {
        shoot("feed", heightDp = 1500) { Feed(feedState) }
        shoot("feed-dark", heightDp = 1500, dark = true) { Feed(feedState) }
        shoot("feed-empty") {
            Feed(FeedUiState(settings = settings, loading = false, insights = feedState.insights.take(0)))
        }
    }

    @Composable
    private fun Feed(state: FeedUiState) {
        FeedContent(
            uiState = state,
            nowMillis = nowMillis,
            onActivityClick = {},
            onRecordClick = {},
            onInsightAction = {},
            onDismissInsight = {},
            onNeedTrack = {},
            onAddManual = {},
        )
    }

    @Test
    fun record() {
        shoot("record-idle") {
            RecordContent(
                state = RecordingState(),
                settings = settings,
                selectedType = ActivityType.RUN,
                intervals = null,
                countdown = -1,
                showBackgroundPrompt = true,
                actions = RecordActions(),
                map = { FakeMap(it, emptyList()) },
            )
        }
        val points = loop().take(60)
        val recording = RecordingState(
            status = TrackingStatus.TRACKING,
            type = ActivityType.RUN,
            distanceMeters = 5_230.0,
            movingTimeMillis = 27 * 60_000L + 14_000,
            currentSpeedMps = 3.3f,
            elevationGainMeters = 41.0,
            points = points,
            lastFix = points.last(),
            gpsAccuracyMeters = 6f,
            heartRateBpm = 156,
            intervalPhase = IntervalPhase("Work 3/6", 5, 13, 48),
        )
        shoot("record-active") {
            RecordContent(recording, settings, ActivityType.RUN, null, -1, false, RecordActions()) { FakeMap(it, points) }
        }
        shoot("record-paused-dark", dark = true) {
            RecordContent(
                recording.copy(status = TrackingStatus.PAUSED, intervalPhase = null),
                settings, ActivityType.RUN, null, -1, false, RecordActions(),
            ) { FakeMap(it, points) }
        }
    }

    @Test
    fun detail() {
        val points = loop()
        val state = DetailUiState(
            activity = activities[0],
            points = points,
            splits = (1..8).map { Split(it, 1000.0, (300 + listOf(4, -6, 9, -12, 2, 14, -3, -9)[it - 1]) * 1000L, (it % 3 - 1) * 6.0) } +
                Split(9, 420.0, 124_000L, 3.0),
            elevationProfile = points.map { it.altitude.toFloat() },
            speedProfile = points.map { it.speedMps },
            heartRateProfile = points.mapNotNull { it.heartRate?.toFloat() },
            heartRateZones = listOf(180_000L, 540_000L, 960_000L, 720_000L, 180_000L),
            settings = settings,
            loading = false,
        )
        shoot("detail", heightDp = 2300) {
            DetailContent(
                uiState = state,
                activity = activities[0],
                segmentEfforts = listOf(EffortForActivity("Riverside mile", 402_000L, 402_000L)),
                rankedEfforts = listOf(
                    RankedEffort("400 m", 400.0, 96_000L, 3, 12),
                    RankedEffort("1 km", 1000.0, 288_000L, 1, 14),
                    RankedEffort("1 mile", 1609.344, 471_000L, 2, 14),
                    RankedEffort("5 km", 5000.0, 1_506_000L, 1, 9),
                ),
                actions = DetailActions(),
                map = { FakeMap(it, points) },
            )
        }
        shoot("detail-manual") {
            DetailContent(
                uiState = DetailUiState(activity = activities[4], settings = settings, loading = false),
                activity = activities[4],
                segmentEfforts = emptyList(),
                rankedEfforts = emptyList(),
                actions = DetailActions(),
                map = {},
            )
        }
    }

    @Test
    fun stats() {
        val state = StatsUiState(
            totalCount = 86,
            totalDistanceMeters = 742_300.0,
            totalMovingTimeMillis = 71 * 3_600_000L,
            totalElevationMeters = 9_840.0,
            chartBuckets = listOf(12, 18, 9, 22, 25, 14, 0, 19, 27, 31, 24, 18).mapIndexed { i, km ->
                ChartBucket("${(i * 7) % 28 + 1}/${8 + i / 4}", km * 1000.0)
            },
            currentStreakWeeks = 6,
            longestStreakWeeks = 11,
            trainingLoad = TrainingLoad(156, 172, false),
            segments = listOf(
                SegmentWithStats(1, "Riverside mile", "RUN", 1610.0, 7, 402_000L),
                SegmentWithStats(2, "Castle hill climb", "RIDE", 2400.0, 3, 518_000L),
            ),
            records = RecordsUiState(
                sport = ActivityType.RUN,
                availableSports = listOf(ActivityType.RUN, ActivityType.RIDE, ActivityType.HIKE),
                efforts = listOf(
                    EffortRecord("1 km", 1000.0, 288_000L, 1, "Morning Run", daysAgo(0), 5_000L, 14, true),
                    EffortRecord("5 km", 5000.0, 1_506_000L, 1, "Morning Run", daysAgo(0), 21_000L, 9, true),
                    EffortRecord("10 km", 10000.0, 3_192_000L, 7, "Sunday long run", daysAgo(46), null, 3, false),
                ),
                milestones = listOf(
                    MilestoneRecord("Longest distance", "21.4 km", "Autumn half · 4 Oct 2026", 9, false),
                    MilestoneRecord("Most climbing", "412 m", "Hill repeats · 19 Sep 2026", 10, false),
                    MilestoneRecord("Biggest week", "47.2 km", "Week of 28 Sep 2026 · 5 activities", null, false),
                ),
            ),
            settings = settings,
            loading = false,
        )
        shoot("stats", heightDp = 1900) { StatsContent(state, StatsActions()) }
        shoot("stats-empty") { StatsContent(StatsUiState(settings = settings, loading = false), StatsActions()) }
    }

    @Test
    fun profile() {
        shoot("profile", heightDp = 2300) {
            ProfileContent(settings, healthConnectAvailable = true, batteryUnrestricted = false, actions = ProfileActions())
        }
    }

    @Test
    fun onboarding() {
        shoot("onboarding") { OnboardingContent(onStart = {}) }
    }
}
