package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreviewOverlay(
    yoloDetector: YoloDetector,
    viewModel: BenchmarkViewModel,
    modifier: Modifier = Modifier,
    onDetectionsUpdated: (List<Detection>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "This real-time benchmarking app requires camera access to process video stream frames with YOLOv11.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6))
                ) {
                    Text("Grant Camera Permission", color = Color.White)
                }
            }
        }
    } else {
        // We have permission, setup CameraX & Overlay
        Box(modifier = modifier.fillMaxSize()) {
            val previewView = remember { PreviewView(context) }
            val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

            // Bind CameraX
            DisposableEffect(lifecycleOwner) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 640))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val frameStart = System.currentTimeMillis()
                        
                        // Extract bitmap directly using built-in CameraX converter
                        val bitmap = try {
                            imageProxy.toBitmap()
                        } catch (e: Exception) {
                            Log.e("CameraPreview", "Failed to convert imageProxy to bitmap", e)
                            null
                        }

                        if (bitmap != null) {
                            // Run object detector & segmentation
                            val (detections, pureLatency) = yoloDetector.detect(bitmap)
                            
                            val frameEnd = System.currentTimeMillis()
                            val e2eLatency = frameEnd - frameStart

                            // Update system benchmark metrics
                            viewModel.updateInferenceMetrics(
                                pureLatency = pureLatency,
                                e2e = e2eLatency,
                                detectionsSize = detections.size
                            )

                            // Send detections up to parent scope for drawing overlay
                            onDetectionsUpdated(detections)
                        }

                        imageProxy.close()
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "Camera Binding Failed", e)
                    }

                }, ContextCompat.getMainExecutor(context))

                onDispose {
                    cameraExecutor.shutdown()
                }
            }

            // Display Render View
            AndroidView(
                factory = {
                    previewView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Draws custom vector boxes, class metrics badges, and digital dot instance mask overlays
 */
@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    classes: List<String>,
    modifier: Modifier = Modifier
) {
    // Elegant standard class colors for segmentation masks & bounding boxes
    val classColors = remember {
        listOf(
            Color(0x9929B6F6), // Sky Blue (Person)
            Color(0x9966BB6A), // Emerald Green (Car)
            Color(0x99FFCA28), // Sunshine Yellow (Bicycle)
            Color(0x99AB47BC), // Royal Purple (Motorcycle)
            Color(0x99FF7043), // Tangerine Orange (Bus)
            Color(0x99EC407A)  // Hot Pink (Train)
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height

        // YOLOv11 input size is 640x640
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

            // 1. Draw segment instance mask (matrix blocks) - Downsampled for fast rendering & stylish tech visual
            val mask = detection.mask
            if (mask != null) {
                val step = 3 // Check every 3rd pixel for extreme responsiveness
                val cellW = (canvasW / 160f) * step
                val cellH = (canvasH / 160f) * step

                for (r in 0 until 160 step step) {
                    for (c in 0 until 160 step step) {
                        val score = mask[r * 160 + c]
                        if (score > 0.45f) { // mask active threshold
                            val mx = c * (canvasW / 160f)
                            val my = r * (canvasH / 160f)
                            
                            drawRect(
                                color = maskColor,
                                topLeft = Offset(mx, my),
                                size = Size(cellW, cellH),
                                style = Fill
                            )
                        }
                    }
                }
            }

            // 2. Draw modern high-contrast bounding box
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

            // 3. Draw class text label badge with native Canvas drawing
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

            // Clean badge background on top-left of box
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
