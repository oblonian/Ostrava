package com.ostrava.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Simple filled line chart used for elevation and pace profiles. */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (values.size < 2) {
        Box(modifier = modifier)
        return
    }
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier) {
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, value ->
            val x = i * stepX
            val y = size.height * (1f - (value - min) / range)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        val fillPath = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0.02f)),
            ),
        )
        drawPath(path, color = lineColor, style = Stroke(width = 4f))
    }
}

data class BarEntry(val label: String, val value: Float)

/** Vertical bar chart built from layout primitives (weekly distance, splits, …). */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    highlightIndex: Int = -1,
    highlightColor: Color = MaterialTheme.colorScheme.secondary,
) {
    val maxValue = entries.maxOfOrNull { it.value }?.takeIf { it > 0f } ?: 1f
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        entries.forEachIndexed { index, entry ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight((entry.value / maxValue).coerceIn(0.02f, 1f))
                            .background(
                                color = if (index == highlightIndex) highlightColor else barColor,
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Horizontal proportional bar, used for split pace visualisation. */
@Composable
fun HorizontalBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .height(18.dp)
            .fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.05f, 1f))
                .background(color, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
            content = content,
        )
    }
}
