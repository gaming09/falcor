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

/**
 * Compose detection boxes for live (WebView + native fallbacks).
 * Uses camera detect width/height aspect for letterbox/pillarbox mapping so
 * boxes stay aligned with object-fit: contain video.
 *
 * [contentAspectRatio] when > 0 maps normalized boxes into the letterboxed
 * video content rect (object-fit: contain) inside the canvas.
 */
@Composable
fun DetectionOverlay(
    boxes: List<DetectionBox>,
    modifier: Modifier = Modifier,
    boxColor: Color = Color(0xFF00E676),
    contentAspectRatio: Float = 0f
) {
    if (boxes.isEmpty()) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = Stroke(width = 2.dp.toPx())
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.GREEN
            textSize = 32f
            isAntiAlias = true
        }
        val ox: Float
        val oy: Float
        val cw: Float
        val ch: Float
        if (contentAspectRatio > 0.01f) {
            val viewAr = size.width / size.height.coerceAtLeast(1f)
            if (viewAr > contentAspectRatio) {
                // pillarbox
                val w = size.height * contentAspectRatio
                ox = (size.width - w) / 2f
                oy = 0f
                cw = w
                ch = size.height
            } else {
                // letterbox
                val h = size.width / contentAspectRatio
                ox = 0f
                oy = (size.height - h) / 2f
                cw = size.width
                ch = h
            }
        } else {
            ox = 0f
            oy = 0f
            cw = size.width
            ch = size.height
        }
        boxes.forEach { box ->
            val l = ox + box.left.coerceIn(0f, 1f) * cw
            val t = oy + box.top.coerceIn(0f, 1f) * ch
            val r = ox + box.right.coerceIn(0f, 1f) * cw
            val b = oy + box.bottom.coerceIn(0f, 1f) * ch
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
            drawContext.canvas.nativeCanvas.drawText(
                label,
                l + 4f,
                (t - 6f).coerceAtLeast(oy + 28f),
                paint
            )
        }
    }
}
