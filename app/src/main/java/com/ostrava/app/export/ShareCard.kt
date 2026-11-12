package com.ostrava.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    ROUTE("Route"),
    MINIMAL("Minimal"),
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
    /** Extra darkening over the photo, 0 (none) to 0.7. */
    val photoDim: Float = 0.2f,
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
 * Draws a shareable activity card onto a bitmap. Pure android.graphics so the same
 * code produces the on-screen preview (at a reduced [scale]) and the exported image.
 */
object ShareCardRenderer {

    const val MAX_STATS = 6

    private const val MARGIN = 72f
    private const val ROW_HEIGHT = 164f
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

    fun render(spec: ShareSpec, content: ShareContent, photo: Bitmap?, scale: Float = 1f): Bitmap {
        val width = spec.aspect.width.toFloat()
        val height = spec.aspect.height.toFloat()
        val bitmap = Bitmap.createBitmap(
            (width * scale).roundToInt(),
            (height * scale).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)

        val usePhoto = spec.template == ShareTemplate.PHOTO && photo != null
        val palette = when {
            usePhoto -> Palette(Color.WHITE, withAlpha(Color.WHITE, 0.8f), ORANGE, Color.WHITE, Color.WHITE, true)
            spec.template == ShareTemplate.MINIMAL ->
                Palette(NAVY, withAlpha(NAVY, 0.6f), ORANGE, ORANGE, Color.WHITE, false)
            else -> {
                // The photo template without a photo falls back to the Midnight gradient.
                val theme = if (spec.template == ShareTemplate.PHOTO) ShareTheme.MIDNIGHT else spec.theme
                Palette(
                    text = Color.WHITE,
                    secondary = withAlpha(Color.WHITE, 0.72f),
                    accent = theme.accent,
                    route = theme.accent,
                    onAccent = if (luminance(theme.accent) > 0.6f) NAVY else Color.WHITE,
                    shadow = false,
                )
            }
        }

        drawBackground(canvas, spec, photo, width, height)

        val selected = spec.stats.mapNotNull { stat -> content.stats.firstOrNull { it.stat == stat } }
            .take(MAX_STATS)
        val hero = if (spec.template == ShareTemplate.MINIMAL) selected.firstOrNull() else null
        val gridStats = if (hero != null) selected.drop(1) else selected

        val textTop = drawTextBlock(
            canvas = canvas,
            width = width,
            height = height,
            content = content,
            stats = gridStats,
            prText = if (spec.showPrBadge) content.prText else null,
            palette = palette,
        )

        drawBrand(canvas, palette)
        var contentTop = MARGIN + 70f
        if (hero != null) {
            contentTop = drawHero(canvas, hero, width, contentTop, palette)
        }

        if (spec.showRoute && content.points.size >= 2) {
            val rect = if (usePhoto) {
                // Over a photo the route is a small overlay so it doesn't fight the picture.
                val size = min(width * 0.36f, textTop - contentTop - 40f)
                RectF(width - MARGIN - size, MARGIN, width - MARGIN, MARGIN + size)
            } else {
                RectF(MARGIN + 24f, contentTop + 24f, width - MARGIN - 24f, textTop - 64f)
            }
            if (rect.width() >= 120f && rect.height() >= 120f) {
                drawRoute(canvas, content.points, rect, palette, thin = usePhoto)
            }
        }
        return bitmap
    }

    private fun drawBackground(canvas: Canvas, spec: ShareSpec, photo: Bitmap?, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when {
            spec.template == ShareTemplate.MINIMAL -> canvas.drawColor(PAPER)
            spec.template == ShareTemplate.PHOTO && photo != null -> {
                // Centre-crop the photo to fill the card.
                val scale = max(width / photo.width, height / photo.height)
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate((width - photo.width * scale) / 2f, (height - photo.height * scale) / 2f)
                }
                canvas.drawBitmap(photo, matrix, paint)
                val dim = spec.photoDim.coerceIn(0f, 0.7f)
                if (dim > 0f) canvas.drawColor(withAlpha(Color.BLACK, dim))
                // Scrims keep the text legible whatever the photo looks like.
                paint.shader = LinearGradient(
                    0f, height * 0.38f, 0f, height,
                    Color.TRANSPARENT, withAlpha(Color.BLACK, 0.82f), Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, height * 0.38f, width, height, paint)
                paint.shader = LinearGradient(
                    0f, 0f, 0f, 300f,
                    withAlpha(Color.BLACK, 0.45f), Color.TRANSPARENT, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width, 300f, paint)
            }
            else -> {
                val theme = if (spec.template == ShareTemplate.PHOTO) ShareTheme.MIDNIGHT else spec.theme
                paint.shader = LinearGradient(
                    0f, 0f, width, height, theme.start, theme.end, Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, width, height, paint)
            }
        }
    }

    private fun drawBrand(canvas: Canvas, palette: Palette) {
        val paint = textPaint(34f, palette.accent, bold = true, shadow = palette.shadow).apply {
            letterSpacing = 0.18f
        }
        canvas.drawText("OSTRAVA", MARGIN, MARGIN + 30f, paint)
    }

    /** Minimal template: one oversized number. Returns the y below it. */
    private fun drawHero(canvas: Canvas, hero: ShareStatValue, width: Float, top: Float, palette: Palette): Float {
        val valuePaint = textPaint(210f, palette.text, bold = true, shadow = false).apply {
            textAlign = Paint.Align.CENTER
        }
        fitText(valuePaint, hero.value, width - 2 * MARGIN)
        val baseline = top + 40f + valuePaint.textSize * 0.82f
        canvas.drawText(hero.value, width / 2f, baseline, valuePaint)

        val labelPaint = textPaint(34f, palette.secondary, bold = true, shadow = false).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.14f
        }
        canvas.drawText(hero.label.uppercase(), width / 2f, baseline + 62f, labelPaint)
        return baseline + 80f
    }

    /**
     * Title, date, optional PR badge and the stats grid, anchored to the bottom edge.
     * Returns the y of the block's top so the route can use the space above it.
     */
    private fun drawTextBlock(
        canvas: Canvas,
        width: Float,
        height: Float,
        content: ShareContent,
        stats: List<ShareStatValue>,
        prText: String?,
        palette: Palette,
    ): Float {
        val columns = when (stats.size) {
            0 -> 0
            1, 2, 3 -> stats.size
            4 -> 2
            else -> 3
        }
        val rows = if (columns == 0) 0 else ceil(stats.size / columns.toFloat()).toInt()
        val gridHeight = if (rows == 0) 0f else rows * ROW_HEIGHT - 40f
        val gridTop = height - MARGIN - gridHeight

        if (columns > 0) {
            val cellWidth = (width - 2 * MARGIN) / columns
            val labelPaint = textPaint(30f, palette.secondary, bold = true, shadow = palette.shadow).apply {
                letterSpacing = 0.1f
            }
            stats.forEachIndexed { index, stat ->
                val x = MARGIN + (index % columns) * cellWidth
                val y = gridTop + (index / columns) * ROW_HEIGHT
                val valuePaint = textPaint(78f, palette.text, bold = true, shadow = palette.shadow)
                fitText(valuePaint, stat.value, cellWidth - 24f)
                canvas.drawText(stat.value, x, y + 78f, valuePaint)
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
