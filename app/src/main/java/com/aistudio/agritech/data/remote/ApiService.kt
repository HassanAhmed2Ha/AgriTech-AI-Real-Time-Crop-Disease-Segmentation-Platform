package com.aistudio.agritech.data.remote

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Retrofit interface for the AgriTech crop disease detection API.
 *
 * Base URL is configured via [ApiClient] using the `AGRITECH_API_URL`
 * build config field (injected from `.env` via the Secrets Gradle Plugin).
 */
interface ApiService {

    /**
     * Submits a camera frame image for crop disease detection.
     *
     * The server runs YOLOv11 segmentation inference and returns a list of
     * detected disease instances along with server-side inference timing.
     *
     * @param image  JPEG-encoded image file as a multipart form field named "image".
     * @return       [DetectionResponse] with detections and server inference latency.
     */
    @Multipart
    @POST("detect")
    suspend fun detectDisease(
        @Part image: MultipartBody.Part
    ): Response<DetectionResponse>
}
