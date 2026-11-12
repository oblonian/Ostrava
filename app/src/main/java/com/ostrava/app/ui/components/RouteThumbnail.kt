package com.ostrava.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ostrava.app.domain.TrackPoint
import kotlin.math.cos

/** Minimal route silhouette drawn straight from track points — no map tiles. */
@Composable
fun RouteThumbnail(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    /** When set, only this index range is drawn in [color]; the rest is faded. */
    highlight: IntRange? = null,
) {
    val faded = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))

        val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = 4.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val offsetX = pad + (size.width - 2 * pad - (spanX * scale).toFloat()) / 2f
        val offsetY = pad + (size.height - 2 * pad - (spanY * scale).toFloat()) / 2f

        fun position(point: TrackPoint) = Offset(
            offsetX + ((point.longitude - minLon) * lonScale * scale).toFloat(),
            offsetY + ((maxLat - point.latitude) * scale).toFloat(),
        )
        val stroke = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        /** One path per pause-separated segment, restricted to [range]. */
        fun draw(range: IntRange, pathColor: Color, style: Stroke) {
            var path: Path? = null
            var previousSegment: Int? = null
            for (index in range) {
                val point = points[index]
                val at = position(point)
                if (path == null || point.segment != previousSegment) {
                    path?.let { drawPath(it, color = pathColor, style = style) }
                    path = Path().apply { moveTo(at.x, at.y) }
                } else {
                    path.lineTo(at.x, at.y)
                }
                previousSegment = point.segment
            }
            path?.let { drawPath(it, color = pathColor, style = style) }
        }

        if (highlight == null) {
            draw(points.indices, color, stroke)
        } else {
            val range = highlight.first.coerceIn(0, points.lastIndex)..highlight.last.coerceIn(0, points.lastIndex)
            draw(points.indices, faded, stroke)
            draw(range, color, Stroke(width = 4f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Mark where the highlighted stretch starts and ends.
            drawCircle(color, radius = 5f.dp.toPx(), center = position(points[range.first]))
            drawCircle(color, radius = 5f.dp.toPx(), center = position(points[range.last]))
        }
    }
}
