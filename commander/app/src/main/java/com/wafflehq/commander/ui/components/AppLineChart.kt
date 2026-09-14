package com.wafflehq.commander.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.wafflehq.commander.ui.theme.AppRole
import com.wafflehq.commander.ui.theme.AppSpacing
import com.wafflehq.commander.ui.theme.AppTheme

private val CHART_HEIGHT = 120.dp
private const val LINE_STROKE_WIDTH_PX = 4f
private const val AREA_FILL_ALPHA = 0.15f

@Composable
fun AppLineChart(
    values: List<Float>,
    emptyLabel: String,
    modifier: Modifier = Modifier,
    role: AppRole = AppRole.Primary,
    valueFormatter: (Float) -> String = { it.toString() },
) {
    if (values.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
        return
    }

    val accent = AppTheme.colors.forRole(role).accent
    val range = computeChartRange(values)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            val widthPx = size.width
            val heightPx = size.height
            val stepX = if (values.size > 1) widthPx / (values.size - 1) else 0f
            val points = values.mapIndexed { index, value ->
                Offset(index * stepX, mapValueToY(value, range, heightPx))
            }
            val linePath = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            val areaPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, heightPx)
                lineTo(points.first().x, heightPx)
                close()
            }
            drawPath(path = areaPath, color = accent.copy(alpha = AREA_FILL_ALPHA))
            drawPath(path = linePath, color = accent, style = Stroke(width = LINE_STROKE_WIDTH_PX))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = valueFormatter(values.min()),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Text(
                text = valueFormatter(values.last()),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurface,
            )
            Text(
                text = valueFormatter(values.max()),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}
