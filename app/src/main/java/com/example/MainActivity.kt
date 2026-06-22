package com.example

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        BenchmarkAppScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun BenchmarkAppScreen() {
    val context = LocalContext.current
    
    // Initialize YOLO detector and keep it inside remembers
    val yoloDetector = remember { YoloDetector(context) }
    val viewModel: BenchmarkViewModel = viewModel()

    // Real-time tracking of detections to render overlay
    var activeDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }

    // Sync Model status with view model
    LaunchedEffect(yoloDetector.modelStatusMessage) {
        viewModel.setModelStatus(yoloDetector.modelStatusMessage)
    }

    // Collect variables from View Model
    val isLogging by viewModel.isLogging.collectAsState()
    val logFileName by viewModel.logFileName.collectAsState()
    val logFilePath by viewModel.logFilePath.collectAsState()
    val loggedCount by viewModel.loggedCount.collectAsState()
    val logDurationSec by viewModel.logDurationSec.collectAsState()

    val pureInferenceLatency by viewModel.pureInferenceLatency.collectAsState()
    val pureInferenceFps by viewModel.pureInferenceFps.collectAsState()
    val e2eLatency by viewModel.e2eLatency.collectAsState()
    val e2eFps by viewModel.e2eFps.collectAsState()
    
    val memoryUsageMB by viewModel.memoryUsageMB.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val rawModelStatus by viewModel.modelStatus.collectAsState()

    var showLogsPanel by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF070709))) {
        
        // 1. Full screen camera view port
        CameraPreviewOverlay(
            yoloDetector = yoloDetector,
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            onDetectionsUpdated = { newList ->
                activeDetections = newList
            }
        )

        // 2. Real-time box & instance mask graphics drawer
        DetectionOverlay(
            detections = activeDetections,
            classes = yoloDetector.classes,
            modifier = Modifier.fillMaxSize()
        )

        // 3. App Top Bar (Android MD3 Sleek Style)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xE0070709))
                .padding(vertical = 12.dp, horizontal = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title & Model Engine state
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Operational heartbeat green/yellow led
                        val infiniteTransition = rememberInfiniteTransition(label = "ledger")
                        val blinkAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "ledgerBlink"
                        )
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (yoloDetector.isSimulationMode) Color(0xFFFFB300) else Color(0xFF00E676))
                                .alpha(blinkAlpha)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "YOLOv11 BENCHMARKING",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = rawModelStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC5CAE9), // Clean slate-indigo
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Battery / Power diagnostics
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "BAT: $batteryLevel%${if (isCharging) " (PWR)" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                batteryLevel < 20 -> Color(0xFFFF5252)
                                batteryLevel < 50 -> Color(0xFFFFB300)
                                else -> Color(0xFF00E676)
                            }
                        )
                        Text(
                            text = "MEM: $memoryUsageMB MB",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF90A4AE)
                        )
                    }
                }
            }
        }

        // 4. Floating logging indicator (REC badge) on the visualizer when active
        if (isLogging) {
            val infiniteTransition = rememberInfiniteTransition(label = "indicator")
            val blinkAlpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "indicatorBlink"
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 90.dp, start = 16.dp)
                    .background(Color(0xD0E53935), shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .alpha(blinkAlpha)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val min = logDurationSec / 60
                val sec = logDurationSec % 60
                Text(
                    text = String.format("REC [%02d:%02d] | %d ticks", min, sec, loggedCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 5. Sleek Research overlay performance console (Lower segment)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp) // Perfect separation for logging FAB
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xD00F111A)), // Translucent Slate/Indigo mix
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0x25FFFFFF))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF81D4FA))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REAL-TIME METRICS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC5CAE9),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Core FPS and Latency calculations - Monospace
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "INFERENCE LATENCY",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF90A4AE),
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$pureInferenceLatency",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Light,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Text(
                                    text = " ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF90A4AE),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Text(
                                text = String.format("%.1f fps", pureInferenceFps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFC5CAE9),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "E2E LATENCY",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF90A4AE),
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$e2eLatency",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = Color(0xFF81D4FA),
                                    fontWeight = FontWeight.Light,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Text(
                                    text = " ms",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF90A4AE),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Text(
                                text = String.format("%.1f fps", e2eFps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF81D4FA),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Divider(color = Color(0x15FFFFFF))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Latency ratio bar + active boxes list count
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Ratio: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF90A4AE),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(50.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0x1BFFFFFF))
                        ) {
                            val pureRatio = if (e2eLatency > 0) pureInferenceLatency.toFloat() / e2eLatency else 0.5f
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight(pureRatio.coerceAtLeast(0.01f))
                                        .background(Color.White)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .weight((1f - pureRatio).coerceAtLeast(0.01f))
                                        .background(Color(0xFF5C6BC0))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Detections: ${activeDetections.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // CSV log diagnostic pathway details
                    if (isLogging) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Log Target: $logFileName",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00E676),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    // Historical logs drawer toggle
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (showLogsPanel) "Hide Local CSV Explorer" else "Show Local CSV Explorer",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF81D4FA),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable {
                                showLogsPanel = !showLogsPanel
                                viewModel.refreshRecentLogs()
                            }
                            .padding(vertical = 4.dp)
                    )

                    AnimatedVisibility(visible = showLogsPanel) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "LATEST EXPORTED CSV TRACKS (Documents/YOLOv11_Benchmark):",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF90A4AE),
                                fontFamily = FontFamily.Monospace
                            )
                            if (viewModel.recentLogs.isEmpty()) {
                                Text(
                                    text = "No logs recorded yet. Start logging with the floating action button below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF78909C),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            } else {
                                val logList = viewModel.recentLogs
                                LazyColumn(
                                    modifier = Modifier
                                        .height(100.dp)
                                        .fillMaxWidth()
                                ) {
                                    items(logList) { logName ->
                                        Text(
                                            text = "• $logName",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFFECEFF1),
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Styled Indigo Floating Action Button
        ExtendedFloatingActionButton(
            onClick = {
                viewModel.toggleLogging()
            },
            icon = {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isLogging) Color.White else Color(0xFFFF5252))
                )
            },
            text = {
                Text(
                    text = if (isLogging) "STOP LOGGING METRICS" else "START LOGGING CSV",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = 12.sp
                )
            },
            containerColor = if (isLogging) Color(0xFFE53935) else Color(0xFF5C6BC0),
            contentColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}
