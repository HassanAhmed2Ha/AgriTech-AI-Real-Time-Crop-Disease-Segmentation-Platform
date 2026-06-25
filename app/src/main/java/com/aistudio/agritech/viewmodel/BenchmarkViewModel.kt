package com.aistudio.agritech.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.agritech.data.model.Detection
import com.aistudio.agritech.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel that owns all benchmark state and coordinates the end-to-end
 * pipeline: camera frame → API call → metric update → optional CSV logging.
 *
 * Latency semantics:
 * - [pureInferenceLatency]  = `inference_ms` reported by the server (model time only).
 * - [networkLatencyMs]      = total round-trip time minus server inference time.
 * - [e2eLatency]            = full client-measured wall-clock time per frame.
 */
class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // --- Logging State ---
    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()

    private val _logFileName = MutableStateFlow("None")
    val logFileName: StateFlow<String> = _logFileName.asStateFlow()

    private val _logFilePath = MutableStateFlow("")
    val logFilePath: StateFlow<String> = _logFilePath.asStateFlow()

    private val _loggedCount = MutableStateFlow(0)
    val loggedCount: StateFlow<Int> = _loggedCount.asStateFlow()

    private val _logDurationSec = MutableStateFlow(0L)
    val logDurationSec: StateFlow<Long> = _logDurationSec.asStateFlow()

    // --- System Metrics ---
    private val _memoryUsageMB = MutableStateFlow(0L)
    val memoryUsageMB: StateFlow<Long> = _memoryUsageMB.asStateFlow()

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    // --- Inference Metrics ---
    private val _pureInferenceLatency = MutableStateFlow(0L)
    /** Server-reported model inference time in ms (from API response `inference_ms`). */
    val pureInferenceLatency: StateFlow<Long> = _pureInferenceLatency.asStateFlow()

    private val _pureInferenceFps = MutableStateFlow(0.0f)
    val pureInferenceFps: StateFlow<Float> = _pureInferenceFps.asStateFlow()

    private val _networkLatencyMs = MutableStateFlow(0L)
    /** Round-trip network time in ms = E2E latency − server inference time. */
    val networkLatencyMs: StateFlow<Long> = _networkLatencyMs.asStateFlow()

    private val _e2eLatency = MutableStateFlow(0L)
    /** Full client-measured wall-clock time per processed frame in ms. */
    val e2eLatency: StateFlow<Long> = _e2eLatency.asStateFlow()

    private val _e2eFps = MutableStateFlow(0.0f)
    val e2eFps: StateFlow<Float> = _e2eFps.asStateFlow()

    private val _activeDetectionsCount = MutableStateFlow(0)
    val activeDetectionsCount: StateFlow<Int> = _activeDetectionsCount.asStateFlow()

    private val _apiStatus = MutableStateFlow("Ready — awaiting frames")
    /** Human-readable API connection status shown in the top bar. */
    val apiStatus: StateFlow<String> = _apiStatus.asStateFlow()

    val recentLogs = mutableStateListOf<String>()

    private var loggingJob: Job? = null
    private var trackingJob: Job? = null
    private var activeFileWriter: FileWriter? = null

    /** Tracks the last processed detections for rendering by the UI layer. */
    private val _currentDetections = MutableStateFlow<List<Detection>>(emptyList())
    val currentDetections: StateFlow<List<Detection>> = _currentDetections.asStateFlow()

    init {
        updateSystemMetrics()
        refreshRecentLogs()

        // Continuous 1-second background polling of battery and memory
        trackingJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                updateSystemMetrics()
                delay(1000)
            }
        }
    }

    /**
     * Accepts a raw camera [Bitmap] and the frame capture start timestamp,
     * encodes it as JPEG, posts it to the detection API, and updates all
     * metric state flows on success.
     *
     * This function is non-blocking and dispatched on [Dispatchers.IO].
     */
    fun analyzeFrame(bitmap: Bitmap, frameStartMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Encode bitmap to JPEG bytes
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val imageBytes = stream.toByteArray()

                val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
                val imagePart = MultipartBody.Part.createFormData(
                    name = "image",
                    filename = "frame.jpg",
                    body = requestBody
                )

                val response = ApiClient.service.detectDisease(imagePart)
                val frameEndMs = System.currentTimeMillis()
                val e2e = frameEndMs - frameStartMs

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val serverInferenceMs = body.inferenceMs
                        val networkMs = (e2e - serverInferenceMs).coerceAtLeast(0L)

                        val detections = body.detections.map { r ->
                            Detection(
                                classId = r.classId,
                                className = r.className,
                                score = r.score,
                                x1 = r.x1,
                                y1 = r.y1,
                                x2 = r.x2,
                                y2 = r.y2,
                                mask = r.mask?.toFloatArray()
                            )
                        }

                        withContext(Dispatchers.Main) {
                            _pureInferenceLatency.value = serverInferenceMs
                            _pureInferenceFps.value =
                                if (serverInferenceMs > 0) 1000f / serverInferenceMs else 0f
                            _networkLatencyMs.value = networkMs
                            _e2eLatency.value = e2e
                            _e2eFps.value = if (e2e > 0) 1000f / e2e else 0f
                            _activeDetectionsCount.value = detections.size
                            _currentDetections.value = detections
                            _apiStatus.value = "API Connected · ${body.modelVersion}"
                        }
                    }
                } else {
                    Log.w("BenchmarkViewModel", "API error ${response.code()}: ${response.message()}")
                    withContext(Dispatchers.Main) {
                        _apiStatus.value = "API Error ${response.code()}"
                    }
                }
            } catch (e: Exception) {
                Log.e("BenchmarkViewModel", "Frame analysis failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _apiStatus.value = "Disconnected — ${e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun updateSystemMetrics() {
        val runtime = Runtime.getRuntime()
        _memoryUsageMB.value = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.let { _batteryLevel.value = it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }

            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.let {
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            Log.e("BenchmarkViewModel", "Battery state unavailable: ${e.message}")
        }
    }

    /** Toggles CSV performance logging on/off. */
    fun toggleLogging() {
        if (_isLogging.value) stopLogging() else startLogging()
    }

    private fun startLogging() {
        if (_isLogging.value) return

        viewModelScope.launch {
            try {
                _isLogging.value = true
                _loggedCount.value = 0
                _logDurationSec.value = 0L

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val filename = "agritech_metrics_$timestamp.csv"
                _logFileName.value = filename

                val logDir = getOutputDir().also { if (!it.exists()) it.mkdirs() }
                val logFile = File(logDir, filename)
                _logFilePath.value = logFile.absolutePath

                withContext(Dispatchers.IO) {
                    val writer = FileWriter(logFile, true)
                    writer.write(
                        "Timestamp,Server_Inference_MS,Pure_FPS,Network_Latency_MS," +
                            "E2E_Latency_MS,E2E_FPS,Memory_Used_MB,Battery_Percent,Is_Charging\n"
                    )
                    writer.flush()
                    activeFileWriter = writer
                }

                Toast.makeText(context, "Logging started → Documents/AgriTech_Benchmark", Toast.LENGTH_LONG).show()

                loggingJob = viewModelScope.launch(Dispatchers.IO) {
                    while (_isLogging.value) {
                        delay(1000)
                        try {
                            activeFileWriter?.let { writer ->
                                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                writer.write(
                                    "$ts,${_pureInferenceLatency.value}," +
                                        "${String.format(Locale.US, "%.2f", _pureInferenceFps.value)}," +
                                        "${_networkLatencyMs.value}," +
                                        "${_e2eLatency.value}," +
                                        "${String.format(Locale.US, "%.2f", _e2eFps.value)}," +
                                        "${_memoryUsageMB.value},${_batteryLevel.value}," +
                                        "${if (_isCharging.value) "YES" else "NO"}\n"
                                )
                                writer.flush()
                                _loggedCount.value++
                                _logDurationSec.value++
                            }
                        } catch (e: Exception) {
                            Log.e("BenchmarkViewModel", "CSV write error: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("BenchmarkViewModel", "Failed to start logging: ${e.message}")
                _isLogging.value = false
                startLoggingFallback()
            }
        }
    }

    private fun startLoggingFallback() {
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "agritech_metrics_$timestamp.csv"
        _logFileName.value = filename

        val logFile = File(fallbackDir, filename)
        _logFilePath.value = logFile.absolutePath

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                val writer = FileWriter(logFile, true)
                writer.write(
                    "Timestamp,Server_Inference_MS,Pure_FPS,Network_Latency_MS," +
                        "E2E_Latency_MS,E2E_FPS,Memory_Used_MB,Battery_Percent,Is_Charging\n"
                )
                writer.flush()
                activeFileWriter = writer
                _isLogging.value = true

                loggingJob = viewModelScope.launch(Dispatchers.IO) {
                    while (_isLogging.value) {
                        delay(1000)
                        try {
                            activeFileWriter?.let { w ->
                                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                w.write(
                                    "$ts,${_pureInferenceLatency.value}," +
                                        "${String.format(Locale.US, "%.2f", _pureInferenceFps.value)}," +
                                        "${_networkLatencyMs.value},${_e2eLatency.value}," +
                                        "${String.format(Locale.US, "%.2f", _e2eFps.value)}," +
                                        "${_memoryUsageMB.value},${_batteryLevel.value}," +
                                        "${if (_isCharging.value) "YES" else "NO"}\n"
                                )
                                w.flush()
                                _loggedCount.value++
                                _logDurationSec.value++
                            }
                        } catch (e: Exception) {
                            Log.e("BenchmarkViewModel", "Fallback CSV write error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BenchmarkViewModel", "Fallback logging failed: ${e.message}")
            }
        }
    }

    private fun stopLogging() {
        if (!_isLogging.value) return
        _isLogging.value = false
        loggingJob?.cancel()
        loggingJob = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                activeFileWriter?.close()
                activeFileWriter = null
            } catch (e: Exception) {
                Log.e("BenchmarkViewModel", "Error closing CSV writer: ${e.message}")
            }
            refreshRecentLogs()
        }

        Toast.makeText(context, "Logging complete — ${_loggedCount.value} ticks saved.", Toast.LENGTH_LONG).show()
    }

    private fun getOutputDir(): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(root, "AgriTech_Benchmark")
    }

    fun refreshRecentLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<String>()

            getOutputDir().takeIf { it.exists() }?.listFiles()
                ?.filter { it.name.endsWith(".csv") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5)
                ?.forEach { list.add("[Public] ${it.name} (${it.length() / 1024} KB)") }

            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?.takeIf { it.exists() }?.listFiles()
                ?.filter { it.name.endsWith(".csv") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(5)
                ?.forEach { list.add("[Sandbox] ${it.name} (${it.length() / 1024} KB)") }

            withContext(Dispatchers.Main) {
                recentLogs.clear()
                recentLogs.addAll(list)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        trackingJob?.cancel()
        loggingJob?.cancel()
        try { activeFileWriter?.close() } catch (_: Exception) { }
    }
}
