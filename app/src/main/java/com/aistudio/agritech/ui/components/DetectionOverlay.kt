package com.aistudio.agritech.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistudio.agritech.data.model.Detection

/**
 * Composable that renders bounding boxes, confidence badges, and segmentation
 * mask overlays on top of the live camera preview for each [Detection].
 *
 * Coordinates are scaled from the model's 640×640 input space to the actual
 * canvas dimensions at draw time, so this composable is layout-agnostic.
 *
 * @param detections  Current frame's list of detected disease instances.
 * @param modifier    Layout modifier applied to the full-size canvas.
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier
) {
    // Class-specific colors aligned to Detection.CLASS_NAMES order
    val classColors = remember {
        listOf(
            Color(0x9929B6F6), // Late Blight — Sky Blue
            Color(0x9966BB6A), // Leaf Miner — Emerald Green
            Color(0x99FFCA28), // Magnesium Deficiency — Sunshine Yellow
            Color(0x99AB47BC), // Nitrogen Deficiency — Royal Purple
            Color(0x99FF7043), // Potassium Deficiency — Tangerine Orange
            Color(0x99EC407A)  // Spotted Wilt Virus — Hot Pink
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height

        // Scale from YOLOv11 640×640 input space to canvas size
        val scaleX = canvasW / 640.0f
        val scaleY = canvasH / 640.0f

        for (detection in detections) {
            val colorBase = classColors.getOrElse(detection.classId) { Color(0x9926A69A) }
            val strokeColor = colorBase.copy(alpha = 1.0f)
            val fillColor = colorBase.copy(alpha = 0.12f)
            val maskColor = colorBase.copy(alpha = 0.5f)

            val x1 = detection.x1 * scaleX
            val y1 = detection.y1 * scaleY
            val x2 = detection.x2 * scaleX
            val y2 = detection.y2 * scaleY

            // 1. Render segmentation mask as a sampled dot-grid overlay
            val mask = detection.mask
            if (mask != null) {
                val step = 3 // Sample every 3rd proto pixel for performance
                val cellW = (canvasW / 160f) * step
                val cellH = (canvasH / 160f) * step

                for (r in 0 until 160 step step) {
                    for (c in 0 until 160 step step) {
                        if (mask[r * 160 + c] > 0.45f) {
                            drawRect(
                                color = maskColor,
                                topLeft = Offset(c * (canvasW / 160f), r * (canvasH / 160f)),
                                size = Size(cellW, cellH),
                                style = Fill
                            )
                        }
                    }
                }
            }

            // 2. Rounded bounding box (stroke + translucent fill)
            drawRoundRect(
                color = strokeColor,
                topLeft = Offset(x1, y1),
                size = Size(x2 - x1, y2 - y1),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(x1, y1),
                size = Size(x2 - x1, y2 - y1),
                cornerRadius = CornerRadius(6f, 6f),
                style = Fill
            )

            // 3. Class label badge
            val textPaint = Paint().asFrameworkPaint().apply {
                color = Color.White.toArgb()
                textSize = 12.sp.toPx()
                isFakeBoldText = true
                isAntiAlias = true
            }

            val label = "${detection.className} ${(detection.score * 100).toInt()}%"
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val badgeWidth = textBounds.width() + 16f
            val badgeHeight = textBounds.height() + 10f
            val badgeX = x1.coerceAtLeast(0f)
            val badgeY = (y1 - badgeHeight).coerceAtLeast(0f)

            drawRoundRect(
                color = strokeColor,
                topLeft = Offset(badgeX, badgeY),
                size = Size(badgeWidth, badgeHeight),
                cornerRadius = CornerRadius(4f, 4f),
                style = Fill
            )

            drawContext.canvas.nativeCanvas.drawText(
                label,
                badgeX + 8f,
                badgeY + badgeHeight - 8f,
                textPaint
            )
        }
    }
}
