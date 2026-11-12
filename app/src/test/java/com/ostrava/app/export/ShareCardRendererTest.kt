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
import org.junit.Assert.assertTrue
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
                val bitmap = ShareCardRenderer.render(spec, content, photo, fakeMap(aspect), scale = 0.5f)
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
            "photo-story-text-top-zoomed" to ShareSpec(
                ShareTemplate.PHOTO, ShareAspect.STORY, textAtTop = true,
                photoZoom = 1.8f, photoCenterX = 0.72f, photoCenterY = 0.5f,
            ),
            "map-story" to ShareSpec(ShareTemplate.MAP, ShareAspect.STORY),
            "map-post-text-top" to ShareSpec(ShareTemplate.MAP, ShareAspect.POST, textAtTop = true),
            "sticker-story" to ShareSpec(ShareTemplate.STICKER, ShareAspect.STORY),
            "route-story-text-top" to ShareSpec(ShareTemplate.ROUTE, ShareAspect.STORY, textAtTop = true),
        )
        val dir = File("build/share-previews").apply { mkdirs() }
        previews.forEach { (name, spec) ->
            val bitmap = ShareCardRenderer.render(spec, content, photo, fakeMap(spec.aspect, spec.textAtTop), scale = 0.5f)
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertEquals(previews.size, dir.listFiles { file -> file.extension == "png" }?.size)
    }

    @Test
    fun `the sticker has a transparent background`() {
        val bitmap = ShareCardRenderer.render(ShareSpec(ShareTemplate.STICKER), content, photo = null)
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width / 2, 40)))
    }

    @Test
    fun `the photo always covers the card however it is dragged`() {
        var spec = ShareSpec(ShareTemplate.PHOTO, ShareAspect.STORY)
        // Drag hard right and down, then zoom in and drag hard the other way.
        spec = ShareCardRenderer.movePhoto(spec, 1600, 1200, dxFraction = 5f, dyFraction = 5f, zoomChange = 1f)
        var placement = ShareCardRenderer.photoPlacement(spec, 1600, 1200)
        assertEquals(0f, placement.left, 0.5f)
        spec = ShareCardRenderer.movePhoto(spec, 1600, 1200, dxFraction = -9f, dyFraction = -9f, zoomChange = 2f)
        placement = ShareCardRenderer.photoPlacement(spec, 1600, 1200)
        assertEquals(2f, spec.photoZoom, 0.001f)
        assertEquals(1080f, placement.right, 0.5f)
        assertEquals(1920f, placement.bottom, 0.5f)
        assertTrue(placement.left <= 0f && placement.top <= 0f)
        // Zoom can't go below "just fills the card".
        spec = ShareCardRenderer.movePhoto(spec, 1600, 1200, 0f, 0f, zoomChange = 0.01f)
        assertEquals(1f, spec.photoZoom, 0.001f)
    }

    @Test
    fun `the map frame centres the route in its target area`() {
        val target = MapBackdropLoader.routeArea(ShareAspect.STORY, textAtTop = false)
        val points = loopTrack()
        val frame = MapMath.frame(points, target)
        val projected = points.map { frame.project(it.latitude, it.longitude) }
        val minX = projected.minOf { it.x }
        val maxX = projected.maxOf { it.x }
        val minY = projected.minOf { it.y }
        val maxY = projected.maxOf { it.y }
        // The route fits inside the target and is centred on it.
        assertTrue(minX >= target.left - 1f && maxX <= target.right + 1f)
        assertTrue(minY >= target.top - 1f && maxY <= target.bottom + 1f)
        assertEquals(target.centerX(), (minX + maxX) / 2f, 2f)
        assertEquals(target.centerY(), (minY + maxY) / 2f, 2f)
        assertTrue(frame.scale >= 1.0 && frame.scale < 2.0)
    }

    /** Stand-in for map tiles: a street-like grid, with the real projection for the route. */
    private fun fakeMap(aspect: ShareAspect, textAtTop: Boolean = false): MapBackdrop {
        val bitmap = Bitmap.createBitmap(aspect.width, aspect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(232, 228, 216))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(170, 211, 223)
        canvas.drawRect(0f, aspect.height * 0.30f, aspect.width.toFloat(), aspect.height * 0.36f, paint)
        paint.color = Color.rgb(200, 230, 190)
        canvas.drawRect(620f, 120f, 980f, 420f, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 14f
        for (x in 0..aspect.width step 180) canvas.drawLine(x.toFloat(), 0f, x + 120f, aspect.height.toFloat(), paint)
        for (y in 0..aspect.height step 200) canvas.drawLine(0f, y.toFloat(), aspect.width.toFloat(), y - 60f, paint)
        val frame = MapMath.frame(loopTrack(), MapBackdropLoader.routeArea(aspect, textAtTop))
        return MapBackdrop(bitmap) { latitude, longitude -> frame.project(latitude, longitude) }
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
