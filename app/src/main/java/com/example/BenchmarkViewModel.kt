package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // Log States
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

    // Real-Time System Metrics State
    private val _memoryUsageMB = MutableStateFlow(0L)
    val memoryUsageMB: StateFlow<Long> = _memoryUsageMB.asStateFlow()

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    // Inference Metrics State
    private val _pureInferenceLatency = MutableStateFlow(0L)
    val pureInferenceLatency: StateFlow<Long> = _pureInferenceLatency.asStateFlow()

    private val _pureInferenceFps = MutableStateFlow(0.0f)
    val pureInferenceFps: StateFlow<Float> = _pureInferenceFps.asStateFlow()

    private val _e2eLatency = MutableStateFlow(0L)
    val e2eLatency: StateFlow<Long> = _e2eLatency.asStateFlow()

    private val _e2eFps = MutableStateFlow(0.0f)
    val e2eFps: StateFlow<Float> = _e2eFps.asStateFlow()

    private val _activeDetectionsCount = MutableStateFlow(0)
    val activeDetectionsCount: StateFlow<Int> = _activeDetectionsCount.asStateFlow()

    private val _modelStatus = MutableStateFlow("Initializing...")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    val recentLogs = mutableStateListOf<String>()

    private var loggingJob: Job? = null
    private var trackingJob: Job? = null
    private var activeFileWriter: FileWriter? = null

    init {
        updateSystemMetrics()
        refreshRecentLogs()
        
        // Start continuous background updates of system status (Battery/Memory)
        trackingJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                updateSystemMetrics()
                delay(1000)
            }
        }
    }

    fun setModelStatus(status: String) {
        _modelStatus.value = status
    }

    /**
     * Updates live inference metrics. Called from Camera Analyzer frame completion.
     */
    fun updateInferenceMetrics(pureLatency: Long, e2e: Long, detectionsSize: Int) {
        _pureInferenceLatency.value = pureLatency
        _pureInferenceFps.value = if (pureLatency > 0) 1000.0f / pureLatency else 0.0f
        
        _e2eLatency.value = e2e
        _e2eFps.value = if (e2e > 0) 1000.0f / e2e else 0.0f

        _activeDetectionsCount.value = detectionsSize
    }

    private fun updateSystemMetrics() {
        // Compute memory footprint
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        _memoryUsageMB.value = usedBytes / (1024 * 1024)

        // Retrieve battery levels & status safely
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                _batteryLevel.value = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, intentFilter)
            if (intent != null) {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                                     status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            Log.e("BenchmarkViewModel", "Error fetching battery specs: ${e.message}")
        }
    }

    /**
     * Toggles CSV Logging service.
     */
    fun toggleLogging() {
        if (_isLogging.value) {
            stopLogging()
        } else {
            startLogging()
        }
    }

    private fun startLogging() {
        if (_isLogging.value) return

        viewModelScope.launch {
            try {
                _isLogging.value = true
                _loggedCount.value = 0
                _logDurationSec.value = 0L

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val filename = "yolo11_metrics_$timestamp.csv"
                _logFileName.value = filename

                // Select directory: Try Public Documents folder first, fallback to App Sandboxed Files if error
                val logDir = getOutputDir()
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }

                val logFile = File(logDir, filename)
                _logFilePath.value = logFile.absolutePath

                withContext(Dispatchers.IO) {
                    val writer = FileWriter(logFile, true)
                    // Write header
                    writer.write("Timestamp,Pure_Latency_MS,Pure_FPS,E2E_Latency_MS,E2E_FPS,Memory_Used_MB,Battery_Percent,Is_Charging\n")
                    writer.flush()
                    activeFileWriter = writer
                }

                Toast.makeText(context, "Logging started. Saving in Documents/YOLOv11_Benchmark", Toast.LENGTH_LONG).show()

                // Launch continuous 1-second writer job
                loggingJob = viewModelScope.launch(Dispatchers.IO) {
                    while (isLogging.value) {
                        try {
                            delay(1000)
                            val writer = activeFileWriter
                            if (writer != null) {
                                val logTimeNow = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                val pureLat = _pureInferenceLatency.value
                                val pureFps = _pureInferenceFps.value
                                val e2e = _e2eLatency.value
                                val e2eFps = _e2eFps.value
                                val mem = _memoryUsageMB.value
                                val bat = _batteryLevel.value
                                val charging = if (_isCharging.value) "YES" else "NO"

                                writer.write("$logTimeNow,$pureLat,${String.format(Locale.US, "%.2f", pureFps)},$e2e,${String.format(Locale.US, "%.2f", e2eFps)},$mem,$bat,$charging\n")
                                writer.flush()

                                _loggedCount.value = _loggedCount.value + 1
                                _logDurationSec.value = _logDurationSec.value + 1L
                            }
                        } catch (e: Exception) {
                            Log.e("BenchmarkViewModel", "Failed appending log: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("BenchmarkViewModel", "Initialization error for CSV writer: ${e.message}", e)
                _isLogging.value = false
                Toast.makeText(context, "Log Creation Failed! Falling back", Toast.LENGTH_SHORT).show()
                // Auto Fallback setup to sandboxed directory
                startLoggingFallback()
            }
        }
    }

    private fun startLoggingFallback() {
        val fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "yolo11_metrics_$timestamp.csv"
        _logFileName.value = filename

        val logFile = File(fallbackDir, filename)
        _logFilePath.value = logFile.absolutePath

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                val writer = FileWriter(logFile, true)
                writer.write("Timestamp,Pure_Latency_MS,Pure_FPS,E2E_Latency_MS,E2E_FPS,Memory_Used_MB,Battery_Percent,Is_Charging\n")
                writer.flush()
                activeFileWriter = writer
                _isLogging.value = true

                loggingJob = viewModelScope.launch(Dispatchers.IO) {
                    while (isLogging.value) {
                        try {
                            delay(1000)
                            val writer2 = activeFileWriter
                            if (writer2 != null) {
                                val logTimeNow = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                                writer2.write("$logTimeNow,${_pureInferenceLatency.value},${String.format(Locale.US, "%.2f", _pureInferenceFps.value)},${_e2eLatency.value},${String.format(Locale.US, "%.2f", _e2eFps.value)},${_memoryUsageMB.value},${_batteryLevel.value},${if (_isCharging.value) "YES" else "NO"}\n")
                                writer2.flush()
                                _loggedCount.value = _loggedCount.value + 1
                                _logDurationSec.value = _logDurationSec.value + 1L
                            }
                        } catch (e: Exception) {
                            Log.e("BenchmarkViewModel", "Log writing exception: ${e.message}")
                        }
                    }
                }
            } catch (e2: Exception) {
                Log.e("BenchmarkViewModel", "Fallback failure: ${e2.message}")
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
                Log.e("BenchmarkViewModel", "Error sealing CSV writer: ${e.message}")
            }
            refreshRecentLogs()
        }

        Toast.makeText(context, "Logging Complete! Total ${_loggedCount.value} ticks saved.", Toast.LENGTH_LONG).show()
    }

    private fun getOutputDir(): File {
        val rootDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(rootDir, "YOLOv11_Benchmark")
    }

    fun refreshRecentLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<String>()
            try {
                // Read from public directory
                val dir = getOutputDir()
                if (dir.exists()) {
                    dir.listFiles()?.filter { it.name.endsWith(".csv") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.take(5)
                        ?.forEach { list.add("[Public] ${it.name} (${it.length() / 1024} KB)") }
                }

                // Read from sandbox directory
                val sDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                if (sDir != null && sDir.exists()) {
                    sDir.listFiles()?.filter { it.name.endsWith(".csv") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.take(5)
                        ?.forEach { list.add("[Sandbox] ${it.name} (${it.length() / 1024} KB)") }
                }
            } catch (e: Exception) {
                Log.e("BenchmarkViewModel", "Error fetching older logs: ${e.message}")
            }

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
        try {
            activeFileWriter?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
