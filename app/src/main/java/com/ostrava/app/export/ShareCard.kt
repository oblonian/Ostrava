package com.ostrava.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.ostrava.app.domain.TrackPoint
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ShareTemplate(val label: String) {
    PHOTO("Photo"),
    MAP("Map"),
    ROUTE("Route"),
    MINIMAL("Minimal"),
    STICKER("Sticker"),
}

enum class ShareAspect(val label: String, val width: Int, val height: Int) {
    STORY("Story 9:16", 1080, 1920),
    POST("Post 4:5", 1080, 1350),
    SQUARE("Square", 1080, 1080),
}

/** Gradient themes for the route template. */
enum class ShareTheme(val label: String, val start: Int, val end: Int, val accent: Int) {
    MIDNIGHT("Midnight", 0xFF22303F.toInt(), 0xFF0B1117.toInt(), 0xFFFC5200.toInt()),
    SUNSET("Sunset", 0xFFFC5200.toInt(), 0xFF6A1B9A.toInt(), 0xFFFFFFFF.toInt()),
    FOREST("Forest", 0xFF14524F.toInt(), 0xFF071A18.toInt(), 0xFF7CFFB2.toInt()),
}

enum class ShareStat(val label: String) {
    DISTANCE("Distance"),
    TIME("Time"),
    PACE("Pace"),
    CLIMB("Elev gain"),
    CALORIES("Calories"),
    HEART_RATE("Avg HR"),
    MAX_SPEED("Max speed"),
}

data class ShareSpec(
    val template: ShareTemplate = ShareTemplate.ROUTE,
    val aspect: ShareAspect = ShareAspect.STORY,
    val theme: ShareTheme = ShareTheme.MIDNIGHT,
    val stats: List<ShareStat> = listOf(ShareStat.DISTANCE, ShareStat.PACE, ShareStat.TIME),
    val showRoute: Boolean = true,
    val showPrBadge: Boolean = true,
    /** Extra darkening over the photo or map, 0 (none) to 0.7. */
    val photoDim: Float = 0.2f,
    /** Puts the title and stats at the top instead of the bottom, e.g. to clear a face. */
    val textAtTop: Boolean = false,
    /** 1 = the photo just fills the card; larger values zoom in. */
    val photoZoom: Float = 1f,
    /** Which point of the photo (0..1 on each axis) sits at the centre of the card. */
    val photoCenterX: Float = 0.5f,
    val photoCenterY: Float = 0.5f,
)

data class ShareStatValue(val stat: ShareStat, val value: String, val label: String)

/** Everything about the activity the card can show; [stats] holds every stat that is available. */
data class ShareContent(
    val title: String,
    val subtitle: String,
    val stats: List<ShareStatValue>,
    val points: List<TrackPoint>,
    val prText: String?,
)

/**
 * A map image the size of the card, plus the projection that places a coordinate on it.
 * Built by [MapBackdropLoader]; the renderer draws the route with [project] so it lines
 * up with the streets underneath.
 */
class MapBackdrop(
    val bitmap: Bitmap,
    val project: (latitude: Double, longitude: Double) -> PointF,
)

/**
 * Draws a shareable activity card onto a bitmap. Pure android.graphics so the same
 * code produces the on-screen preview (at a reduced [scale]) and the exported image.
 */
object ShareCardRenderer {

    const val MAX_STATS = 6
    const val MAX_PHOTO_ZOOM = 4f

    private const val MARGIN = 72f
    private const val ROW_HEIGHT = 164f
    private const val MIN_ROUTE_SIZE = 120f
    private val ORANGE = 0xFFFC5200.toInt()
    private val NAVY = 0xFF1B2733.toInt()
    private val PAPER = 0xFFF6F3EE.toInt()

    private class Palette(
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val route: Int,
        val onAccent: Int,
        val shadow: Boolean,
    )

    /** What is behind the text, which decides the colours and how the route is drawn. */
    private enum class Backdrop { PHOTO, MAP, TRANSPARENT, GRADIENT, PAPER }

    /** Where the photo is drawn so that it covers the card with the requested zoom and pan. */
    fun photoPlacement(spec: ShareSpec, photoWidth: Int, photoHeight: Int): RectF {
        val width = spec.aspect.width.toFloat()
        val height = spec.aspect.height.toFloat()
        val scale = max(width / photoWidth, height / photoHeight) *
            spec.photoZoom.coerceIn(1f, MAX_PHOTO_ZOOM)
        val drawWidth = photoWidth * scale
        val drawHeight = photoHeight * scale
        // Clamp the centre so the photo always covers the whole card.
        val halfX = width / (2f * drawWidth)
        val halfY = height / (2f * drawHeight)
        val centerX = spec.photoCenterX.coerceIn(halfX, 1f - halfX)
        val centerY = spec.photoCenterY.coerceIn(halfY, 1f - halfY)
        val left = width / 2f - centerX * drawWidth
        val top = height / 2f - centerY * drawHeight
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    /**
     * Applies a drag ([dxFraction], [dyFraction] as fractions of the card's width and
     * height) and a pinch ([zoomChange]) to the photo framing.
     */
    fun movePhoto(
        spec: ShareSpec,
        photoWidth: Int,
        photoHeight: Int,
        dxFraction: Float,
        dyFraction: Float,
        zoomChange: Float,
    ): ShareSpec {
        val zoomed = spec.copy(photoZoom = (spec.photoZoom * zoomChange).coerceIn(1f, MAX_PHOTO_ZOOM))
        val placement = photoPlacement(zoomed, photoWidth, photoHeight)
        val width = spec.aspect.width.toFloat()
        val height = spec.aspect.height.toFloat()
        val moved = zoomed.copy(
            // Dragging right shows more of the photo's left side, so the centre moves left.
            photoCenterX = (width / 2f - placement.left) / placement.width() - dxFraction * width / placement.width(),
            photoCenterY = (height / 2f - placement.top) / placement.height() - dyFraction * height / placement.height(),
        )
        // Re-clamp through photoPlacement so the stored centre never drifts out of range.
        val clamped = photoPlacement(moved, photoWidth, photoHeight)
        return moved.copy(
            photoCenterX = (width / 2f - clamped.left) / clamped.width(),
            photoCenterY = (height / 2f - clamped.top) / clamped.height(),
        )
    }

    fun render(
        spec: ShareSpec,
        content: ShareContent,
        photo: Bitmap?,
        map: MapBackdrop? = null,
        scale: Float = 1f,
    ): Bitmap {
        val width = spec.aspect.width.toFloat()
        val height = spec.aspect.height.toFloat()
        val bitmap = Bitmap.createBitmap(
            (width * scale).roundToInt(),
            (height * scale).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)

        val backdrop = when (spec.template) {
            ShareTemplate.PHOTO -> if (photo != null) Backdrop.PHOTO else Backdrop.GRADIENT
            ShareTemplate.MAP -> if (map != null) Backdrop.MAP else Backdrop.GRADIENT
            ShareTemplate.STICKER -> Backdrop.TRANSPARENT
            ShareTemplate.MINIMAL -> Backdrop.PAPER
            ShareTemplate.ROUTE -> Backdrop.GRADIENT
        }
        // Templates that fall back to a gradient (no photo yet, map unavailable) use Midnight.
        val theme = if (spec.template == ShareTemplate.ROUTE) spec.theme else ShareTheme.MIDNIGHT
        val palette = when (backdrop) {
            Backdrop.PHOTO, Backdrop.TRANSPARENT ->
                Palette(Color.WHITE, withAlpha(Color.WHITE, 0.85f), ORANGE, Color.WHITE, Color.WHITE, true)
            Backdrop.MAP ->
                Palette(Color.WHITE, withAlpha(Color.WHITE, 0.85f), ORANGE, ORANGE, Color.WHITE, true)
            Backdrop.PAPER -> Palette(NAVY, withAlpha(NAVY, 0.6f), ORANGE, ORANGE, Color.WHITE, false)
            Backdrop.GRADIENT -> Palette(
                text = Color.WHITE,
                secondary = withAlpha(Color.WHITE, 0.72f),
                accent = theme.accent,
                route = theme.accent,
                onAccent = if (luminance(theme.accent) > 0.6f) NAVY else Color.WHITE,
                shadow = false,
            )
        }
        // The minimal card is built around its big number at the top, so its text stays below.
        val textAtTop = spec.textAtTop && backdrop != Backdrop.PAPER

        when (backdrop) {
            Backdrop.PHOTO -> drawPhoto(canvas, spec, photo!!, width, height, textAtTop)
            Backdrop.MAP -> drawMap(canvas, spec, map!!, width, height, textAtTop)
            Backdrop.TRANSPARENT -> Unit
            Backdrop.PAPER -> canvas.drawColor(PAPER)
            Backdrop.GRADIENT -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(0f, 0f, width, height, theme.start, theme.end, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, width, height, paint)
            }
        }

        val selected = spec.stats.mapNotNull { stat -> content.stats.firstOrNull { it.stat == stat } }
            .take(MAX_STATS)
        val hero = if (backdrop == Backdrop.PAPER) selected.firstOrNull() else null
        val gridStats = if (hero != null) selected.drop(1) else selected
        val prText = if (spec.showPrBadge) content.prText else null

        drawBrand(canvas, palette)
        val belowBrand = MARGIN + 70f
        val blockHeight = textBlockHeight(gridStats.size, prText != null)
        val blockBottom = if (textAtTop) belowBrand + 24f + blockHeight else height - MARGIN
        val blockTop = drawTextBlock(canvas, width, blockBottom, content, gridStats, prText, palette)

        // The space left over for the route (and the minimal card's big number).
        var freeTop = if (textAtTop) blockBottom + 64f else belowBrand
        val freeBottom = if (textAtTop) height - MARGIN else blockTop - 64f

        val wantsRoute = spec.showRoute && content.points.size >= 2
        if (hero != null) {
            val heroHeight = heroHeight(hero, width, palette)
            val roomForRoute = freeBottom - (freeTop + heroHeight + 24f)
            // With no route below it, the hero number sits in the middle of the free space.
            val top = if (wantsRoute && roomForRoute >= MIN_ROUTE_SIZE) freeTop
            else freeTop + ((blockTop - freeTop - heroHeight) / 2f - 20f).coerceAtLeast(0f)
            freeTop = drawHero(canvas, hero, width, top, palette)
        }

        if (wantsRoute) {
            when (backdrop) {
                Backdrop.MAP -> drawProjectedRoute(canvas, content.points, map!!)
                Backdrop.PHOTO -> {
                    // Over a photo the route is a small corner overlay so it doesn't fight the picture.
                    val size = min(width * 0.36f, freeBottom - freeTop - 40f)
                    val top = if (textAtTop) height - MARGIN - size else MARGIN
                    val rect = RectF(width - MARGIN - size, top, width - MARGIN, top + size)
                    if (size >= MIN_ROUTE_SIZE) drawRoute(canvas, content.points, rect, palette, thin = true)
                }
                else -> {
                    val rect = RectF(MARGIN + 24f, freeTop + 24f, width - MARGIN - 24f, freeBottom)
                    if (rect.width() >= MIN_ROUTE_SIZE && rect.height() >= MIN_ROUTE_SIZE) {
                        drawRoute(canvas, content.points, rect, palette, thin = false)
                    }
                }
            }
        }

        if (backdrop == Backdrop.MAP) drawAttribution(canvas, width, height, textAtTop)
        return bitmap
    }

    private fun drawPhoto(canvas: Canvas, spec: ShareSpec, photo: Bitmap, width: Float, height: Float, textAtTop: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(photo, null, photoPlacement(spec, photo.width, photo.height), paint)
        drawDimAndScrims(canvas, spec.photoDim, width, height, textAtTop)
    }

    private fun drawMap(canvas: Canvas, spec: ShareSpec, map: MapBackdrop, width: Float, height: Float, textAtTop: Boolean) {
        // Muted colours keep the map from competing with the route and the text.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.55f) })
        }
        canvas.drawBitmap(map.bitmap, null, RectF(0f, 0f, width, height), paint)
        drawDimAndScrims(canvas, max(spec.photoDim, 0.15f), width, height, textAtTop)
    }

    /** Darkens the backdrop and adds gradients behind the text so it stays legible. */
    private fun drawDimAndScrims(canvas: Canvas, dimAmount: Float, width: Float, height: Float, textAtTop: Boolean) {
        val dim = dimAmount.coerceIn(0f, 0.7f)
        if (dim > 0f) canvas.drawColor(withAlpha(Color.BLACK, dim))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strong = withAlpha(Color.BLACK, 0.82f)
        val light = withAlpha(Color.BLACK, 0.45f)
        if (textAtTop) {
            paint.shader = LinearGradient(0f, 0f, 0f, height * 0.62f, strong, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, height * 0.62f, paint)
            paint.shader = LinearGradient(0f, height - 300f, 0f, height, Color.TRANSPARENT, light, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, height - 300f, width, height, paint)
        } else {
            paint.shader = LinearGradient(0f, height * 0.38f, 0f, height, Color.TRANSPARENT, strong, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, height * 0.38f, width, height, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, 300f, light, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, 300f, paint)
        }
    }

    /** Map data must be credited wherever it is shown. */
    private fun drawAttribution(canvas: Canvas, width: Float, height: Float, textAtTop: Boolean) {
        val paint = textPaint(22f, withAlpha(Color.WHITE, 0.75f), bold = false, shadow = true).apply {
            textAlign = Paint.Align.RIGHT
        }
        val baseline = if (textAtTop) height - 28f else MARGIN + 28f
        canvas.drawText("© OpenStreetMap contributors", width - MARGIN, baseline, paint)
    }

    /** Draws the route in map coordinates so it follows the streets on the map backdrop. */
    private fun drawProjectedRoute(canvas: Canvas, points: List<TrackPoint>, map: MapBackdrop) {
        val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 19f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(10f, 0f, 3f, withAlpha(Color.BLACK, 0.5f))
        }
        val line = Paint(casing).apply {
            clearShadowLayer()
            color = ORANGE
            strokeWidth = 11f
        }
        val positions = points.map { map.project(it.latitude, it.longitude) }
        val paths = points.indices.groupBy { points[it].segment }.toSortedMap().values
            .filter { it.size >= 2 }
            .map { indices ->
                Path().apply {
                    indices.forEachIndexed { i, index ->
                        val position = positions[index]
                        if (i == 0) moveTo(position.x, position.y) else lineTo(position.x, position.y)
                    }
                }
            }
        paths.forEach { canvas.drawPath(it, casing) }
        paths.forEach { canvas.drawPath(it, line) }

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE }
        val first = positions.first()
        val last = positions.last()
        canvas.drawCircle(first.x, first.y, 15f, dot)
        canvas.drawCircle(first.x, first.y, 8f, core)
        canvas.drawCircle(last.x, last.y, 15f, core)
        canvas.drawCircle(last.x, last.y, 7f, dot)
    }

    private fun drawBrand(canvas: Canvas, palette: Palette) {
        val paint = textPaint(34f, palette.accent, bold = true, shadow = palette.shadow).apply {
            letterSpacing = 0.18f
        }
        canvas.drawText("OSTRAVA", MARGIN, MARGIN + 30f, paint)
    }

    private fun heroPaint(hero: ShareStatValue, width: Float, palette: Palette): TextPaint =
        textPaint(210f, palette.text, bold = true, shadow = false).apply {
            textAlign = Paint.Align.CENTER
            fitText(this, hero.value, width - 2 * MARGIN)
        }

    private fun heroHeight(hero: ShareStatValue, width: Float, palette: Palette): Float =
        40f + heroPaint(hero, width, palette).textSize * 0.82f + 80f

    /** Minimal template: one oversized number. Returns the y below it. */
    private fun drawHero(canvas: Canvas, hero: ShareStatValue, width: Float, top: Float, palette: Palette): Float {
        val valuePaint = heroPaint(hero, width, palette)
        val baseline = top + 40f + valuePaint.textSize * 0.82f
        canvas.drawText(hero.value, width / 2f, baseline, valuePaint)

        val labelPaint = textPaint(34f, palette.secondary, bold = true, shadow = false).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.14f
        }
        canvas.drawText(hero.label.uppercase(), width / 2f, baseline + 62f, labelPaint)
        return baseline + 80f
    }

    private fun gridColumns(statCount: Int): Int = when (statCount) {
        0 -> 0
        1, 2, 3 -> statCount
        4 -> 2
        else -> 3
    }

    private fun gridHeight(statCount: Int): Float {
        val columns = gridColumns(statCount)
        if (columns == 0) return 0f
        return ceil(statCount / columns.toFloat()).toInt() * ROW_HEIGHT - 40f
    }

    /** Height of the text block drawn by [drawTextBlock], so it can also be anchored to the top. */
    private fun textBlockHeight(statCount: Int, hasBadge: Boolean): Float {
        val grid = gridHeight(statCount)
        return grid + (if (grid > 0f) 72f else 0f) + 116f + (if (hasBadge) 78f else 0f)
    }

    /**
     * Title, date, optional PR badge and the stats grid, laid out upwards from [bottom].
     * Returns the y of the block's top.
     */
    private fun drawTextBlock(
        canvas: Canvas,
        width: Float,
        bottom: Float,
        content: ShareContent,
        stats: List<ShareStatValue>,
        prText: String?,
        palette: Palette,
    ): Float {
        val columns = gridColumns(stats.size)
        val gridTop = bottom - gridHeight(stats.size)

        if (columns > 0) {
            val cellWidth = (width - 2 * MARGIN) / columns
            val labelPaint = textPaint(30f, palette.secondary, bold = true, shadow = palette.shadow).apply {
                letterSpacing = 0.1f
            }
            stats.forEachIndexed { index, stat ->
                val x = MARGIN + (index % columns) * cellWidth
                val y = gridTop + (index / columns) * ROW_HEIGHT
                drawStatValue(canvas, stat.value, x, y + 78f, cellWidth - 36f, palette)
                canvas.drawText(stat.label.uppercase(), x, y + 122f, labelPaint)
            }
        }

        var cursor = gridTop
        if (columns > 0) {
            val dividerY = gridTop - 34f
            val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(palette.text, 0.25f)
                strokeWidth = 2f
            }
            canvas.drawLine(MARGIN, dividerY, width - MARGIN, dividerY, divider)
            cursor = dividerY - 38f
        }

        val subtitlePaint = textPaint(34f, palette.secondary, bold = false, shadow = palette.shadow)
        canvas.drawText(content.subtitle, MARGIN, cursor, subtitlePaint)

        val titlePaint = textPaint(62f, palette.text, bold = true, shadow = palette.shadow)
        val title = TextUtils.ellipsize(
            content.title, titlePaint, width - 2 * MARGIN, TextUtils.TruncateAt.END,
        ).toString()
        val titleBaseline = cursor - 54f
        canvas.drawText(title, MARGIN, titleBaseline, titlePaint)
        var top = titleBaseline - 62f

        if (prText != null) {
            val badgePaint = textPaint(30f, palette.onAccent, bold = true, shadow = false)
            val label = TextUtils.ellipsize(
                prText, badgePaint, width - 2 * MARGIN - 56f, TextUtils.TruncateAt.END,
            ).toString()
            val badgeHeight = 60f
            val badgeBottom = top - 18f
            val rect = RectF(
                MARGIN, badgeBottom - badgeHeight,
                MARGIN + badgePaint.measureText(label) + 56f, badgeBottom,
            )
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
            canvas.drawRoundRect(rect, badgeHeight / 2f, badgeHeight / 2f, fill)
            canvas.drawText(label, rect.left + 28f, rect.bottom - 20f, badgePaint)
            top = rect.top
        }
        return top
    }

    /** Draws "10.42 km" as a large number with a smaller unit so columns don't crowd each other. */
    private fun drawStatValue(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        maxWidth: Float,
        palette: Palette,
    ) {
        val split = text.indexOf(' ')
        val number = if (split > 0) text.substring(0, split) else text
        val unit = if (split > 0) text.substring(split + 1) else ""
        val numberPaint = textPaint(78f, palette.text, bold = true, shadow = palette.shadow)
        val unitPaint = textPaint(40f, palette.text, bold = true, shadow = palette.shadow)
        val gap = if (unit.isEmpty()) 0f else 12f
        val total = numberPaint.measureText(number) + gap + unitPaint.measureText(unit)
        val shrink = if (total > maxWidth && total > 0f) maxWidth / total else 1f
        numberPaint.textSize *= shrink
        unitPaint.textSize *= shrink
        canvas.drawText(number, x, baseline, numberPaint)
        if (unit.isNotEmpty()) {
            canvas.drawText(unit, x + numberPaint.measureText(number) + gap * shrink, baseline, unitPaint)
        }
    }

    private fun drawRoute(canvas: Canvas, points: List<TrackPoint>, bounds: RectF, palette: Palette, thin: Boolean) {
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))

        val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = min(bounds.width() / spanX, bounds.height() / spanY).toFloat()
        val offsetX = bounds.left + (bounds.width() - (spanX * scale).toFloat()) / 2f
        val offsetY = bounds.top + (bounds.height() - (spanY * scale).toFloat()) / 2f
        fun x(point: TrackPoint) = offsetX + ((point.longitude - minLon) * lonScale * scale).toFloat()
        fun y(point: TrackPoint) = offsetY + ((maxLat - point.latitude) * scale).toFloat()

        val strokeWidth = if (thin) 9f else 13f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.route
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (palette.shadow) setShadowLayer(10f, 0f, 3f, withAlpha(Color.BLACK, 0.55f))
        }
        val glow = Paint(line).apply {
            clearShadowLayer()
            color = withAlpha(palette.route, 0.22f)
            this.strokeWidth = strokeWidth * 2.6f
        }

        val paths = points.groupBy { it.segment }.toSortedMap().values
            .filter { it.size >= 2 }
            .map { segment ->
                Path().apply {
                    segment.forEachIndexed { i, point ->
                        if (i == 0) moveTo(x(point), y(point)) else lineTo(x(point), y(point))
                    }
                }
            }
        if (!thin) paths.forEach { canvas.drawPath(it, glow) }
        paths.forEach { canvas.drawPath(it, line) }

        // Start is a hollow dot, finish a solid one.
        val first = points.first()
        val last = points.last()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.route }
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (palette.route == Color.WHITE) NAVY else Color.WHITE
        }
        val radius = strokeWidth * 1.25f
        canvas.drawCircle(x(first), y(first), radius, dot)
        canvas.drawCircle(x(first), y(first), radius * 0.5f, core)
        canvas.drawCircle(x(last), y(last), radius, dot)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean, shadow: Boolean): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (shadow) setShadowLayer(8f, 0f, 2f, withAlpha(Color.BLACK, 0.6f))
        }

    /** Shrinks the paint's text size until [text] fits in [maxWidth]. */
    private fun fitText(paint: Paint, text: String, maxWidth: Float) {
        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize = paint.textSize * maxWidth / measured
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
}
