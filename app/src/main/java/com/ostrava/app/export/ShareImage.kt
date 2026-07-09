package com.ostrava.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.formatDate
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos

/** Renders a social-media summary card (route + stats) and opens the share sheet. */
object ShareImage {

    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private val ORANGE = Color.rgb(252, 82, 0)
    private val NAVY = Color.rgb(27, 39, 51)

    fun share(context: Context, activity: ActivityEntity, points: List<TrackPoint>, imperial: Boolean) {
        val bitmap = render(activity, points, imperial)
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "share_${activity.id}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        bitmap.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share activity"))
    }

    private fun render(activity: ActivityEntity, points: List<TrackPoint>, imperial: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(NAVY)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            textSize = 40f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 76f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            textSize = 36f
        }
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ORANGE
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val type = ActivityType.fromName(activity.type)
        canvas.drawText(activity.title, 64f, 120f, titlePaint)
        canvas.drawText("${type.label} · ${formatDate(activity.startTime)}", 64f, 180f, subtitlePaint)

        drawRoute(canvas, points, RectF(64f, 240f, WIDTH - 64f, 840f))

        val stats = buildList {
            add(formatDistance(activity.distanceMeters, imperial) to "Distance")
            add(formatDuration(activity.movingTimeMillis) to "Moving time")
            if (type.usesPace) {
                add(formatPace(activity.avgSpeedMps, imperial) to "Avg pace")
            } else {
                add(formatSpeed(activity.avgSpeedMps, imperial) to "Avg speed")
            }
            add(formatElevation(activity.elevationGainMeters, imperial) to "Climb")
        }
        val columnWidth = (WIDTH - 128) / 2f
        stats.forEachIndexed { index, (value, label) ->
            val x = 64f + (index % 2) * columnWidth
            val y = 980f + (index / 2) * 170f
            canvas.drawText(value, x, y, valuePaint)
            canvas.drawText(label, x, y + 48f, labelPaint)
        }

        canvas.drawText("OSTRAVA", 64f, HEIGHT - 64f, brandPaint)
        return bitmap
    }

    private fun drawRoute(canvas: Canvas, points: List<TrackPoint>, bounds: RectF) {
        if (points.size < 2) return
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val midLat = Math.toRadians((minLat + maxLat) / 2)
        val lonScale = cos(midLat)

        val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = minOf(bounds.width() / spanX, bounds.height() / spanY).toFloat()
        val offsetX = bounds.left + (bounds.width() - (spanX * scale).toFloat()) / 2f
        val offsetY = bounds.top + (bounds.height() - (spanY * scale).toFloat()) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ORANGE
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        points.groupBy { it.segment }.toSortedMap().values.forEach { segment ->
            if (segment.size < 2) return@forEach
            val path = Path()
            segment.forEachIndexed { i, p ->
                val x = offsetX + ((p.longitude - minLon) * lonScale * scale).toFloat()
                val y = offsetY + ((maxLat - p.latitude) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }
}
