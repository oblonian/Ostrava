package com.ostrava.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.ostrava.app.domain.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/** Where a card-sized map is cut from the world: tile zoom, scale and top-left corner. */
class MapFrame(
    val zoom: Int,
    /** Card pixels per tile pixel (1..2): tiles are enlarged a little to fit the route exactly. */
    val scale: Double,
    /** World-pixel coordinates (at [zoom]) of the card's top-left corner. */
    val left: Double,
    val top: Double,
) {
    fun project(latitude: Double, longitude: Double): PointF = PointF(
        ((MapMath.worldX(longitude, zoom) - left) * scale).toFloat(),
        ((MapMath.worldY(latitude, zoom) - top) * scale).toFloat(),
    )
}

/** Web Mercator maths shared by the loader and its tests. */
object MapMath {
    const val TILE_SIZE = 256
    private const val MAX_ZOOM = 18

    fun worldX(longitude: Double, zoom: Int): Double =
        (longitude + 180.0) / 360.0 * TILE_SIZE * 2.0.pow(zoom)

    fun worldY(latitude: Double, zoom: Int): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.05, 85.05))
        return (1.0 - ln(tan(radians) + 1.0 / kotlin.math.cos(radians)) / PI) / 2.0 * TILE_SIZE * 2.0.pow(zoom)
    }

    /**
     * Picks the zoom and offset that fit the route's bounding box into [target]
     * (a rectangle in card pixels) and centre it there.
     */
    fun frame(points: List<TrackPoint>, target: RectF): MapFrame {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }

        // Size of the bounding box in world pixels at zoom 0.
        val spanX = (worldX(maxLon, 0) - worldX(minLon, 0)).coerceAtLeast(1e-7)
        val spanY = (worldY(minLat, 0) - worldY(maxLat, 0)).coerceAtLeast(1e-7)
        val exactZoom = log2(min(target.width() / spanX, target.height() / spanY))
            .coerceIn(1.0, MAX_ZOOM.toDouble())
        val zoom = floor(exactZoom).toInt()
        val scale = 2.0.pow(exactZoom - zoom)

        val centerX = (worldX(minLon, zoom) + worldX(maxLon, zoom)) / 2.0
        val centerY = (worldY(minLat, zoom) + worldY(maxLat, zoom)) / 2.0
        return MapFrame(
            zoom = zoom,
            scale = scale,
            left = centerX - target.centerX() / scale,
            top = centerY - target.centerY() / scale,
        )
    }
}

/**
 * Builds the map backdrop for the share card by stitching OpenStreetMap tiles.
 * Tiles are cached on disk, so re-rendering the same route doesn't hit the network again.
 */
object MapBackdropLoader {

    private const val MAX_TILES = 60
    private const val USER_AGENT = "Ostrava/1.3 (Android; com.ostrava.app)"

    /** The part of the card the route should occupy, leaving room for the text. */
    fun routeArea(aspect: ShareAspect, textAtTop: Boolean): RectF {
        val width = aspect.width.toFloat()
        val height = aspect.height.toFloat()
        return if (textAtTop) RectF(130f, height * 0.50f, width - 130f, height - 150f)
        else RectF(130f, 190f, width - 130f, height * 0.50f)
    }

    /** Returns null when the map can't be fetched (offline, or the route is unusable). */
    suspend fun load(
        context: Context,
        points: List<TrackPoint>,
        aspect: ShareAspect,
        textAtTop: Boolean,
    ): MapBackdrop? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        val frame = MapMath.frame(points, routeArea(aspect, textAtTop))
        val tileSize = MapMath.TILE_SIZE
        val tilesAcross = 1 shl frame.zoom

        val firstX = floor(frame.left / tileSize).toInt()
        val lastX = floor((frame.left + aspect.width / frame.scale) / tileSize).toInt()
        val firstY = floor(frame.top / tileSize).toInt().coerceAtLeast(0)
        val lastY = floor((frame.top + aspect.height / frame.scale) / tileSize).toInt()
            .coerceAtMost(tilesAcross - 1)
        val coordinates = (firstY..lastY).flatMap { y -> (firstX..lastX).map { x -> x to y } }
        if (coordinates.isEmpty() || coordinates.size > MAX_TILES) return@withContext null

        val cacheDir = File(context.cacheDir, "share-tiles").apply { mkdirs() }
        val limit = Semaphore(4)
        val tiles = coroutineScope {
            coordinates.map { (x, y) ->
                async {
                    limit.withPermit {
                        // Wrap around the antimeridian.
                        val wrappedX = ((x % tilesAcross) + tilesAcross) % tilesAcross
                        Triple(x, y, fetchTile(cacheDir, frame.zoom, wrappedX, y))
                    }
                }
            }.awaitAll()
        }
        // A map with holes in it looks broken; fall back to the plain gradient instead.
        if (tiles.count { it.third == null } > tiles.size * 0.3) return@withContext null

        val bitmap = Bitmap.createBitmap(aspect.width, aspect.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(221, 221, 221))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        tiles.forEach { (x, y, tile) ->
            if (tile == null) return@forEach
            val left = ((x * tileSize - frame.left) * frame.scale).toFloat()
            val top = ((y * tileSize - frame.top) * frame.scale).toFloat()
            val size = (tileSize * frame.scale).toFloat()
            // Half a pixel of overlap hides seams between scaled tiles.
            canvas.drawBitmap(tile, null, RectF(left, top, left + size + 0.5f, top + size + 0.5f), paint)
        }
        MapBackdrop(bitmap) { latitude, longitude -> frame.project(latitude, longitude) }
    }

    private fun fetchTile(cacheDir: File, zoom: Int, x: Int, y: Int): Bitmap? {
        val cached = File(cacheDir, "${zoom}_${x}_$y.png")
        if (cached.exists()) {
            BitmapFactory.decodeFile(cached.path)?.let { return it }
        }
        return try {
            val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val bytes = connection.inputStream.use { it.readBytes() }
                cached.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}
