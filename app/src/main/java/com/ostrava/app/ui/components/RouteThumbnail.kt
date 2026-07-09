package com.ostrava.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
) {
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

        points.groupBy { it.segment }.toSortedMap().values.forEach { segment ->
            if (segment.size < 2) return@forEach
            val path = Path()
            segment.forEachIndexed { i, p ->
                val x = offsetX + ((p.longitude - minLon) * lonScale * scale).toFloat()
                val y = offsetY + ((maxLat - p.latitude) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = color,
                style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
