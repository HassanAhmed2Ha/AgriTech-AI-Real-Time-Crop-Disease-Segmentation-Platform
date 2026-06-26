package com.aistudio.agritech.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Full-screen CameraX preview composable that requests camera permission and
 * streams decoded [Bitmap] frames via [onFrameCaptured].
 *
 * This composable is intentionally decoupled from inference logic — it only
 * delivers raw frames upward. The caller ([BenchmarkViewModel]) decides what
 * to do with each frame (e.g., send to the detection API).
 *
 * @param modifier          Layout modifier applied to the root container.
 * @param onFrameCaptured   Callback invoked on every analyzed camera frame,
 *                          providing the raw [Bitmap] and the wall-clock
 *                          timestamp (ms) of frame capture start.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreviewOverlay(
    modifier: Modifier = Modifier,
    isAnalyzing: java.util.concurrent.atomic.AtomicBoolean,
    onFrameCaptured: (bitmap: Bitmap, frameStartMs: Long) -> Unit
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
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        PermissionDeniedScreen(
            onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        val previewView = remember { PreviewView(context) }
        val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

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
                    if (isAnalyzing.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val frameStartMs = System.currentTimeMillis()

                    val bitmap = try {
                        imageProxy.toBitmap()
                    } catch (e: Exception) {
                        Log.e("CameraPreviewOverlay", "Failed to decode ImageProxy to Bitmap", e)
                        null
                    }

                    if (bitmap != null) {
                        isAnalyzing.set(true)
                        onFrameCaptured(bitmap, frameStartMs)
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
                    Log.e("CameraPreviewOverlay", "Camera binding failed", e)
                }

            }, ContextCompat.getMainExecutor(context))

            onDispose {
                cameraExecutor.shutdown()
            }
        }

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

/** Shown when the user has denied camera permission. */
@Composable
private fun PermissionDeniedScreen(onRetry: () -> Unit) {
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
                contentDescription = "Camera permission required",
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
                text = "This app requires camera access to capture frames and send them to the crop disease detection API.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6))
            ) {
                Text("Grant Camera Permission", color = Color.White)
            }
        }
    }
}
