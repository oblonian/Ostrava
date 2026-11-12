package com.ostrava.app.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.ostrava.app.domain.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the share cards with real (native) graphics and writes them to
 * build/share-previews so the designs can be eyeballed from CI output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareCardRendererTest {

    private val allStats = listOf(
        ShareStatValue(ShareStat.DISTANCE, "10.42 km", "Distance"),
        ShareStatValue(ShareStat.TIME, "52:18", "Time"),
        ShareStatValue(ShareStat.PACE, "5:01 /km", "Pace"),
        ShareStatValue(ShareStat.CLIMB, "124 m", "Elev gain"),
        ShareStatValue(ShareStat.CALORIES, "712 kcal", "Calories"),
        ShareStatValue(ShareStat.HEART_RATE, "156 bpm", "Avg HR"),
        ShareStatValue(ShareStat.MAX_SPEED, "18.4 km/h", "Max speed"),
    )

    private val content = ShareContent(
        title = "Sunday long run along the river",
        subtitle = "Run · 8 Nov 2026",
        stats = allStats,
        points = loopTrack(),
        prText = "🏆 PR · 10 km",
    )

    @Test
    fun `every template renders at the requested size`() {
        val photo = fakePhoto()
        for (template in ShareTemplate.entries) {
            for (aspect in ShareAspect.entries) {
                val spec = ShareSpec(template = template, aspect = aspect)
                val bitmap = ShareCardRenderer.render(spec, content, photo, scale = 0.5f)
                assertEquals(aspect.width / 2, bitmap.width)
                assertEquals(aspect.height / 2, bitmap.height)
            }
        }
    }

    @Test
    fun `renders without a route, stats, badge or photo`() {
        val bare = content.copy(points = emptyList(), prText = null)
        for (template in ShareTemplate.entries) {
            val spec = ShareSpec(template = template, stats = emptyList(), showRoute = false)
            val bitmap = ShareCardRenderer.render(spec, bare, photo = null)
            assertEquals(spec.aspect.width, bitmap.width)
            assertEquals(spec.aspect.height, bitmap.height)
        }
    }

    @Test
    fun `write preview images`() {
        val photo = fakePhoto()
        val six = ShareStat.entries.take(6)
        val previews = mapOf(
            "route-story-midnight" to ShareSpec(ShareTemplate.ROUTE, ShareAspect.STORY, ShareTheme.MIDNIGHT),
            "route-post-sunset" to ShareSpec(
                ShareTemplate.ROUTE, ShareAspect.POST, ShareTheme.SUNSET,
                stats = listOf(ShareStat.DISTANCE, ShareStat.TIME, ShareStat.PACE, ShareStat.CLIMB),
            ),
            "route-square-forest" to ShareSpec(ShareTemplate.ROUTE, ShareAspect.SQUARE, ShareTheme.FOREST),
            "minimal-story" to ShareSpec(ShareTemplate.MINIMAL, ShareAspect.STORY),
            "minimal-square" to ShareSpec(ShareTemplate.MINIMAL, ShareAspect.SQUARE),
            "photo-story" to ShareSpec(ShareTemplate.PHOTO, ShareAspect.STORY),
            "photo-post-six-stats" to ShareSpec(ShareTemplate.PHOTO, ShareAspect.POST, stats = six),
            "photo-square" to ShareSpec(ShareTemplate.PHOTO, ShareAspect.SQUARE),
        )
        val dir = File("build/share-previews").apply { mkdirs() }
        previews.forEach { (name, spec) ->
            val bitmap = ShareCardRenderer.render(spec, content, photo, scale = 0.5f)
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertEquals(previews.size, dir.listFiles { file -> file.extension == "png" }?.size)
    }

    /** A wobbly closed loop, roughly 5 km around. */
    private fun loopTrack(): List<TrackPoint> = (0..360 step 2).map { degrees ->
        val t = Math.toRadians(degrees.toDouble())
        TrackPoint(
            latitude = 50.08 + 0.008 * sin(t) + 0.0025 * sin(3 * t),
            longitude = 14.42 + 0.014 * cos(t) + 0.004 * cos(2 * t),
            altitude = 200.0,
            timeMillis = degrees * 5_000L,
            speedMps = 3.3f,
            segment = 0,
        )
    }

    /** Stand-in for a landscape photo: a sunset sky over dark hills. */
    private fun fakePhoto(): Bitmap {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, 1200f,
            intArrayOf(0xFF2B5A9E.toInt(), 0xFFF2A65A.toInt(), 0xFFFFE0B2.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 1600f, 1200f, paint)
        paint.shader = null
        paint.color = Color.rgb(255, 244, 214)
        canvas.drawCircle(1150f, 620f, 90f, paint)
        paint.color = Color.rgb(46, 74, 62)
        canvas.drawPath(
            Path().apply {
                moveTo(0f, 1200f); lineTo(0f, 800f); lineTo(350f, 620f); lineTo(700f, 820f)
                lineTo(1050f, 660f); lineTo(1600f, 860f); lineTo(1600f, 1200f); close()
            },
            paint,
        )
        paint.color = Color.rgb(24, 40, 36)
        canvas.drawPath(
            Path().apply {
                moveTo(0f, 1200f); lineTo(0f, 980f); lineTo(500f, 900f); lineTo(1000f, 1010f)
                lineTo(1600f, 930f); lineTo(1600f, 1200f); close()
            },
            paint,
        )
        return bitmap
    }
}
