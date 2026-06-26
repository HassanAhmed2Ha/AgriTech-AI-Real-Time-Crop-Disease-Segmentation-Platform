package com.aistudio.agritech

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.agritech.BuildConfig
import com.aistudio.agritech.data.model.Detection
import com.aistudio.agritech.data.remote.ApiClient
import com.aistudio.agritech.ui.components.CameraPreviewOverlay
import com.aistudio.agritech.ui.components.DetectionOverlay
import com.aistudio.agritech.ui.theme.MyApplicationTheme
import com.aistudio.agritech.viewmodel.BenchmarkViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize API client once at app start using the build-config injected URL
        ApiClient.initialize(BuildConfig.AGRITECH_API_URL)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        AgriTechBenchmarkScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun AgriTechBenchmarkScreen() {
    val viewModel: BenchmarkViewModel = viewModel()

    // Collect all observable state from the ViewModel
    val isLogging by viewModel.isLogging.collectAsState()
    val logFileName by viewModel.logFileName.collectAsState()
    val loggedCount by viewModel.loggedCount.collectAsState()
    val logDurationSec by viewModel.logDurationSec.collectAsState()
    val pureInferenceLatency by viewModel.pureInferenceLatency.collectAsState()
    val pureInferenceFps by viewModel.pureInferenceFps.collectAsState()
    val networkLatencyMs by viewModel.networkLatencyMs.collectAsState()
    val e2eLatency by viewModel.e2eLatency.collectAsState()
    val e2eFps by viewModel.e2eFps.collectAsState()
    val memoryUsageMB by viewModel.memoryUsageMB.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val apiStatus by viewModel.apiStatus.collectAsState()
    val currentDetections by viewModel.currentDetections.collectAsState()

    var showLogsPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
    ) {

        // ── Layer 1: Full-screen live camera feed ──────────────────────────
        CameraPreviewOverlay(
            modifier = Modifier.fillMaxSize(),
            isAnalyzing = viewModel.isAnalyzing,
            onFrameCaptured = { bitmap, frameStartMs ->
                viewModel.analyzeFrame(bitmap, frameStartMs)
            }
        )

        // ── Layer 2: Disease detection box + mask overlay ──────────────────
        DetectionOverlay(
            detections = currentDetections,
            modifier = Modifier.fillMaxSize()
        )

        // ── Layer 3: Top status bar ────────────────────────────────────────
        TopStatusBar(
            apiStatus = apiStatus,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            memoryUsageMB = memoryUsageMB
        )

        // ── Layer 4: REC badge (visible only while logging) ───────────────
        if (isLogging) {
            RecordingBadge(
                logDurationSec = logDurationSec,
                loggedCount = loggedCount,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 90.dp, start = 16.dp)
            )
        }

        // ── Layer 5: Real-time metrics console ────────────────────────────
        MetricsConsole(
            pureInferenceLatency = pureInferenceLatency,
            pureInferenceFps = pureInferenceFps,
            networkLatencyMs = networkLatencyMs,
            e2eLatency = e2eLatency,
            e2eFps = e2eFps,
            detectionCount = currentDetections.size,
            isLogging = isLogging,
            logFileName = logFileName,
            showLogsPanel = showLogsPanel,
            recentLogs = viewModel.recentLogs,
            onToggleLogs = {
                showLogsPanel = !showLogsPanel
                viewModel.refreshRecentLogs()
            },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp)
        )

        // ── Layer 6: Logging FAB ───────────────────────────────────────────
        ExtendedFloatingActionButton(
            onClick = { viewModel.toggleLogging() },
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

// ── Sub-composables ────────────────────────────────────────────────────────────

@Composable
private fun TopStatusBar(
    apiStatus: String,
    batteryLevel: Int,
    isCharging: Boolean,
    memoryUsageMB: Long
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "ledBlink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ledBlinkAlpha"
    )

    val isConnected = apiStatus.startsWith("API Connected")
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE0070709))
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF00E676) else Color(0xFFFFB300))
                            .alpha(blinkAlpha)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AGRITECH CROP DISEASE DETECTOR",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp
                    )
                }
                Text(
                    text = apiStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFC5CAE9),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "BAT: $batteryLevel%${if (isCharging) " ⚡" else ""}",
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
                Spacer(modifier = Modifier.width(8.dp))
                // Info icon that toggles the dropdown menu
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Developer Links",
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "GitHub Repository"
                                )
                            },
                            text = { Text("GitHub Repository") },
                            onClick = {
                                menuExpanded = false
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/HassanAhmed2Ha/AgriTech-AI-Real-Time-Crop-Disease-Segmentation-Platform")
                                )
                                context.startActivity(intent)
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Developer Portfolio"
                                )
                            },
                            text = { Text("Developer Portfolio") },
                            onClick = {
                                menuExpanded = false
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://hassan-ahmed-portfolio.vercel.app/")
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingBadge(
    logDurationSec: Long,
    loggedCount: Int,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recBlink")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recBlinkAlpha"
    )

    Row(
        modifier = modifier
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
        Text(
            text = String.format(
                "REC [%02d:%02d] | %d ticks",
                logDurationSec / 60,
                logDurationSec % 60,
                loggedCount
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun MetricsConsole(
    pureInferenceLatency: Long,
    pureInferenceFps: Float,
    networkLatencyMs: Long,
    e2eLatency: Long,
    e2eFps: Float,
    detectionCount: Int,
    isLogging: Boolean,
    logFileName: String,
    showLogsPanel: Boolean,
    recentLogs: List<String>,
    onToggleLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xD00F111A)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0x25FFFFFF))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {

                // Header dot
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
                        "REAL-TIME METRICS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFC5CAE9),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                // Three-column metric layout: Server Inference | Network | E2E
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricColumn(
                        label = "SERVER INFERENCE",
                        valueMs = pureInferenceLatency,
                        fps = pureInferenceFps,
                        valueColor = Color.White,
                        fpsColor = Color(0xFFC5CAE9),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricColumn(
                        label = "NETWORK LATENCY",
                        valueMs = networkLatencyMs,
                        fps = null,
                        valueColor = Color(0xFFFFCA28),
                        fpsColor = Color(0xFFFFCA28),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    MetricColumn(
                        label = "E2E LATENCY",
                        valueMs = e2eLatency,
                        fps = e2eFps,
                        valueColor = Color(0xFF81D4FA),
                        fpsColor = Color(0xFF81D4FA),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = Color(0x15FFFFFF))
                Spacer(modifier = Modifier.height(10.dp))

                // Inference ratio bar + detection count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Ratio: ",
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
                        val serverRatio = if (e2eLatency > 0) pureInferenceLatency.toFloat() / e2eLatency else 0.33f
                        val networkRatio = if (e2eLatency > 0) networkLatencyMs.toFloat() / e2eLatency else 0.33f
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(serverRatio.coerceAtLeast(0.01f))
                                    .background(Color.White)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(networkRatio.coerceAtLeast(0.01f))
                                    .background(Color(0xFFFFCA28))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight((1f - serverRatio - networkRatio).coerceAtLeast(0.01f))
                                    .background(Color(0xFF5C6BC0))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Det: $detectionCount",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (isLogging) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Log: $logFileName",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00E676),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (showLogsPanel) "Hide CSV Explorer" else "Show CSV Explorer",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF81D4FA),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable(onClick = onToggleLogs)
                        .padding(vertical = 4.dp)
                )

                AnimatedVisibility(visible = showLogsPanel) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "LATEST EXPORTED CSV (Documents/AgriTech_Benchmark):",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF90A4AE),
                            fontFamily = FontFamily.Monospace
                        )
                        if (recentLogs.isEmpty()) {
                            Text(
                                "No logs yet. Tap START LOGGING CSV below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF78909C),
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .height(100.dp)
                                    .fillMaxWidth()
                            ) {
                                items(recentLogs) { name ->
                                    Text(
                                        "• $name",
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
}

@Composable
private fun MetricColumn(
    label: String,
    valueMs: Long,
    fps: Float?,
    valueColor: Color,
    fpsColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF90A4AE),
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$valueMs",
                style = MaterialTheme.typography.headlineLarge,
                color = valueColor,
                fontWeight = FontWeight.Light
            )
            Text(
                " ms",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF90A4AE),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (fps != null) {
            Text(
                String.format("%.1f fps", fps),
                style = MaterialTheme.typography.bodyMedium,
                color = fpsColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
