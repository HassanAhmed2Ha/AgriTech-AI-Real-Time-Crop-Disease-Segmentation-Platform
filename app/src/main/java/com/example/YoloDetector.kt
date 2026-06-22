package com.example

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.random.Random

class YoloDetector(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    var isSimulationMode = false
        private set
    
    var modelStatusMessage = "Initializing..."
        private set

    val classes = listOf("Person", "Car", "Bicycle", "Motorcycle", "Bus", "Train")

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            
            // Try loading model from assets
            val assetManager = context.assets
            val modelFileName = "yolo11s_seg_fp16.onnx"
            
            val modelBytes: ByteArray
            try {
                val inputStream = assetManager.open(modelFileName)
                modelBytes = inputStream.readBytes()
                inputStream.close()
            } catch (e: IOException) {
                throw IOException("Model file $modelFileName not found in assets.")
            }

            // Check if it's our placeholder file
            val isPlaceholder = modelBytes.size < 500 || String(modelBytes.take(20).toByteArray()).contains("placeholder")
            if (isPlaceholder) {
                throw IOException("Placeholder model file detected (not a valid ONNX compilation).")
            }

            ortSession = ortEnv?.createSession(modelBytes, sessionOptions)
            isSimulationMode = false
            modelStatusMessage = "ONNX Run Mode: Active (yolo11s_seg_fp16.onnx loaded)"
            Log.i("YoloDetector", "Successfully loaded ONNX Runtime environment & model.")
        } catch (e: Exception) {
            isSimulationMode = true
            modelStatusMessage = "Simulation Mode (Model file missing or placeholder: ${e.message})"
            Log.w("YoloDetector", "Falling back to simulation mode: ${e.message}")
        }
    }

    /**
     * Interface to run object detection & segmentation.
     * Returns a pair of: Detections list, ONNX Pure Inference Latency (ms)
     */
    fun detect(bitmap: Bitmap): Pair<List<Detection>, Long> {
        if (isSimulationMode) {
            // Emulate pure inference running time
            val simPureInferenceTime = Random.nextLong(18, 38)
            try {
                Thread.sleep(simPureInferenceTime)
            } catch (e: InterruptedException) {
                // Ignore
            }
            return Pair(generateSimulatedDetections(), simPureInferenceTime)
        }

        val session = ortSession ?: return Pair(emptyList(), 0L)
        val env = ortEnv ?: return Pair(emptyList(), 0L)

        val startTime = System.currentTimeMillis()
        try {
            // 1. Preprocess bitmap to 640x640 float list [1, 3, 640, 640] BCHW
            val size = 640
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val floatBuffer = FloatBuffer.allocate(1 * 3 * size * size)
            val intValues = IntArray(size * size)
            scaledBitmap.getPixels(intValues, 0, size, 0, 0, size, size)

            // Normalize and write R channel
            for (i in 0 until size * size) {
                val pixel = intValues[i]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                floatBuffer.put(r)
            }
            // G channel
            for (i in 0 until size * size) {
                val pixel = intValues[i]
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                floatBuffer.put(g)
            }
            // B channel
            for (i in 0 until size * size) {
                val pixel = intValues[i]
                val b = (pixel and 0xFF) / 255.0f
                floatBuffer.put(b)
            }
            floatBuffer.rewind()

            val inputShape = longArrayOf(1, 3, size.toLong(), size.toLong())
            val inputName = session.inputNames.iterator().next()
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, inputShape)

            // 2. RUN INFERENCE (Measure Pure Inference Latency)
            val infStart = System.currentTimeMillis()
            val results = session.run(mapOf(inputName to inputTensor))
            val infEnd = System.currentTimeMillis()
            val pureInferenceLatency = infEnd - infStart

            inputTensor.close()

            // 3. Post-process outputs
            // Dynamic identification of outputs by shape
            var output0: OnnxTensor? = null // Boxes/scores: [1, 42, 8400]
            var output1: OnnxTensor? = null // Protos: [1, 32, 160, 160]

            for (output in results) {
                val tensor = output.value as? OnnxTensor
                val shape = tensor?.info?.shape
                if (shape != null) {
                    if (shape.size == 3 && shape[1] == 42L && shape[2] == 8400L) {
                        output0 = tensor
                    } else if (shape.size == 4 && shape[1] == 32L && shape[2] == 160L && shape[3] == 160L) {
                        output1 = tensor
                    }
                }
            }

            if (output0 == null || output1 == null) {
                results.close()
                return Pair(emptyList(), pureInferenceLatency)
            }

            // Extract box data [1, 42, 8400] as a 1D flat array for fast iteration
            val boxData = FloatArray(1 * 42 * 8400)
            output0.floatBuffer.get(boxData)

            // Extract proto data [1, 32, 160, 160]
            val protoData = FloatArray(1 * 32 * 160 * 160)
            output1.floatBuffer.get(protoData)

            results.close()

            // 4. Candidate selection & NMS
            val confThreshold = 0.35f
            val iouThreshold = 0.45f
            val candidates = mutableListOf<Candidate>()

            for (col in 0 until 8400) {
                // Find highest scoring class score
                var maxScore = 0.0f
                var classId = -1
                for (c in 0 until 6) {
                    val score = boxData[(4 + c) * 8400 + col]
                    if (score > maxScore) {
                        maxScore = score
                        classId = c
                    }
                }

                if (maxScore > confThreshold) {
                    val cx = boxData[0 * 8400 + col]
                    val cy = boxData[1 * 8400 + col]
                    val w = boxData[2 * 8400 + col]
                    val h = boxData[3 * 8400 + col]

                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    val x2 = cx + w / 2f
                    val y2 = cy + h / 2f

                    val coeffs = FloatArray(32) { k -> boxData[(10 + k) * 8400 + col] }
                    candidates.add(Candidate(x1, y1, x2, y2, maxScore, classId, coeffs))
                }
            }

            // Perform NMS
            candidates.sortByDescending { it.score }
            val selectedCandidates = mutableListOf<Candidate>()
            for (cand in candidates) {
                var keep = true
                for (sel in selectedCandidates) {
                    if (iou(cand, sel) > iouThreshold) {
                        keep = false
                        break
                    }
                }
                if (keep) {
                    selectedCandidates.add(cand)
                    if (selectedCandidates.size >= 15) break // Performance throttle
                }
            }

            // 5. Generate final detections & masks
            val list = selectedCandidates.map { cand ->
                // Generate 160x160 instance segmentation mask via Matrix Multiplication
                val mask = FloatArray(160 * 160)
                
                // Keep mask values inside bounding box coordinates clamped to proto scale [160, 160]
                val bx1 = ((cand.x1 / 640f) * 160f).coerceIn(0f, 160f)
                val by1 = ((cand.y1 / 640f) * 160f).coerceIn(0f, 160f)
                val bx2 = ((cand.x2 / 640f) * 160f).coerceIn(0f, 160f)
                val by2 = ((cand.y2 / 640f) * 160f).coerceIn(0f, 160f)

                for (r in by1.toInt() until by2.toInt()) {
                    for (c in bx1.toInt() until bx2.toInt()) {
                        var sum = 0f
                        for (k in 0 until 32) {
                            val protoIdx = k * 160 * 160 + r * 160 + c
                            sum += cand.coeffs[k] * protoData[protoIdx]
                        }
                        // Apply sigmoid activation
                        val sig = 1.0f / (1.0f + exp(-sum))
                        mask[r * 160 + c] = sig
                    }
                }

                Detection(
                    classId = cand.classId,
                    className = classes.getOrElse(cand.classId) { "Unknown" },
                    score = cand.score,
                    x1 = cand.x1,
                    y1 = cand.y1,
                    x2 = cand.x2,
                    y2 = cand.y2,
                    mask = mask
                )
            }

            return Pair(list, pureInferenceLatency)

        } catch (e: Exception) {
            Log.e("YoloDetector", "Inference error: ${e.message}", e)
            return Pair(emptyList(), 0L)
        }
    }

    private fun iou(c1: Candidate, c2: Candidate): Float {
        val x1 = maxOf(c1.x1, c2.x1)
        val y1 = maxOf(c1.y1, c2.y1)
        val x2 = minOf(c1.x2, c2.x2)
        val y2 = minOf(c1.y2, c2.y2)
        val intersection = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (c1.x2 - c1.x1) * (c1.y2 - c1.y1)
        val area2 = (c2.x2 - c2.x1) * (c2.y2 - c2.y1)
        val union = area1 + area2 - intersection
        return if (union > 0) intersection / union else 0f
    }

    private data class Candidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int,
        val coeffs: FloatArray
    )

    /**
     * Simulation mode generator to display realistic moving detections in real-time.
     */
    private fun generateSimulatedDetections(): List<Detection> {
        val list = mutableListOf<Detection>()
        val timeNow = System.currentTimeMillis() / 1500f // smooth pace

        // 1. Person
        val px1 = 150f + kotlin.math.sin(timeNow) * 40f
        val py1 = 120f + kotlin.math.cos(timeNow * 0.7f) * 20f
        val pw = 160f
        val ph = 320f
        val pMask = createSimulatedMask(px1, py1, px1 + pw, py1 + ph, isCircular = false)
        list.add(
            Detection(
                classId = 0,
                className = classes[0],
                score = 0.82f + kotlin.math.sin(timeNow * 2f) * 0.05f,
                x1 = px1,
                y1 = py1,
                x2 = px1 + pw,
                y2 = py1 + ph,
                mask = pMask
            )
        )

        // 2. Car
        val cx1 = 300f + kotlin.math.cos(timeNow * 0.5f) * 90f
        val cy1 = 380f + kotlin.math.sin(timeNow * 0.8f) * 15f
        val cw = 260f
        val ch = 160f
        val cMask = createSimulatedMask(cx1, cy1, cx1 + cw, cy1 + ch, isCircular = false)
        list.add(
            Detection(
                classId = 1,
                className = classes[1],
                score = 0.94f + kotlin.math.cos(timeNow) * 0.02f,
                x1 = cx1,
                y1 = cy1,
                x2 = cx1 + cw,
                y2 = cy1 + ch,
                mask = cMask
            )
        )

        // 3. Optional Bicycle
        if (kotlin.math.sin(timeNow * 0.3f) > -0.2f) {
            val bx1 = 100f + kotlin.math.sin(timeNow * 1.2f) * 20f
            val by1 = 400f
            val bw = 120f
            val bh = 100f
            val bMask = createSimulatedMask(bx1, by1, bx1 + bw, by1 + bh, isCircular = true)
            list.add(
                Detection(
                    classId = 2,
                    className = classes[2],
                    score = 0.73f,
                    x1 = bx1,
                    y1 = by1,
                    x2 = bx1 + bw,
                    y2 = by1 + bh,
                    mask = bMask
                )
            )
        }

        return list
    }

    private fun createSimulatedMask(x1: Float, y1: Float, x2: Float, y2: Float, isCircular: Boolean): FloatArray {
        val mask = FloatArray(160 * 160)
        val px1 = ((x1 / 640f) * 160f).coerceIn(0f, 160f)
        val py1 = ((y1 / 640f) * 160f).coerceIn(0f, 160f)
        val px2 = ((x2 / 640f) * 160f).coerceIn(0f, 160f)
        val py2 = ((y2 / 640f) * 160f).coerceIn(0f, 160f)

        val cx = (px1 + px2) / 2f
        val cy = (py1 + py2) / 2f
        val rx = (px2 - px1) / 2f
        val ry = (py2 - py1) / 2f

        for (r in py1.toInt() until py2.toInt()) {
            for (c in px1.toInt() until px2.toInt()) {
                if (isCircular) {
                    val dx = (c - cx) / if (rx > 0) rx else 1f
                    val dy = (r - cy) / if (ry > 0) ry else 1f
                    if (dx * dx + dy * dy <= 1.0f) {
                        mask[r * 160 + c] = 0.85f
                    }
                } else {
                    // Draw a nice organic shape inside the box
                    val dx = (c - cx) / if (rx > 0) rx else 1f
                    val dy = (r - cy) / if (ry > 0) ry else 1f
                    // Rounded margins
                    val score = 1.0f - (dx * dx * dx * dx + dy * dy * dy * dy)
                    if (score > 0.15f) {
                        mask[r * 160 + c] = 0.8f
                    }
                }
            }
        }
        return mask
    }
}

data class Detection(
    val classId: Int,
    val className: String,
    val score: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val mask: FloatArray?
)
