package com.aistudio.agritech.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Top-level response envelope returned by the FastAPI `/detect` endpoint.
 *
 * @param detections     List of detected crop disease instances.
 * @param inferenceMs    Server-side pure model inference time in milliseconds.
 * @param modelVersion   Identifier of the model used on the server (e.g. "yolo11s-seg-v1").
 * @param imageWidth     Width of the image the server ran inference on (pixels).
 * @param imageHeight    Height of the image the server ran inference on (pixels).
 */
@JsonClass(generateAdapter = true)
data class DetectionResponse(
    @Json(name = "detections") val detections: List<RemoteDetection>,
    @Json(name = "inference_ms") val inferenceMs: Long,
    @Json(name = "model_version") val modelVersion: String,
    @Json(name = "image_width") val imageWidth: Int,
    @Json(name = "image_height") val imageHeight: Int
)

/**
 * A single detection result as returned by the server.
 *
 * Bounding box coordinates are in the server's model input space (0–640).
 * The optional [mask] is a flattened 160×160 float array encoded as a JSON array.
 */
@JsonClass(generateAdapter = true)
data class RemoteDetection(
    @Json(name = "class_id") val classId: Int,
    @Json(name = "class_name") val className: String,
    @Json(name = "score") val score: Float,
    @Json(name = "x1") val x1: Float,
    @Json(name = "y1") val y1: Float,
    @Json(name = "x2") val x2: Float,
    @Json(name = "y2") val y2: Float,
    @Json(name = "mask") val mask: List<Float>? = null
)
