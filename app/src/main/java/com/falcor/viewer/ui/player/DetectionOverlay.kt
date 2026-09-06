package com.falcor.viewer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.falcor.viewer.data.model.DetectionBox

@Composable
fun DetectionOverlay(
    boxes: List<DetectionBox>,
    modifier: Modifier = Modifier,
    boxColor: Color = Color(0xFF00E676)
) {
    if (boxes.isEmpty()) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = Stroke(width = 2.dp.toPx())
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 32f
            isAntiAlias = true
        }
        boxes.forEach { box ->
            val l = box.left.coerceIn(0f, 1f) * size.width
            val t = box.top.coerceIn(0f, 1f) * size.height
            val r = box.right.coerceIn(0f, 1f) * size.width
            val b = box.bottom.coerceIn(0f, 1f) * size.height
            drawRect(
                color = boxColor,
                topLeft = Offset(l, t),
                size = Size((r - l).coerceAtLeast(1f), (b - t).coerceAtLeast(1f)),
                style = stroke
            )
            val label = buildString {
                append(box.label)
                box.score?.let { append(" ${(it * 100).toInt()}%") }
            }
            drawContext.canvas.nativeCanvas.drawText(label, l + 4f, (t - 6f).coerceAtLeast(28f), paint)
        }
    }
}
