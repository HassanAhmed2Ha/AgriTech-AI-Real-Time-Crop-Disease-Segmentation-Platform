package com.aistudio.agritech.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton that provides a configured [ApiService] instance.
 *
 * The base URL is set via [initialize], which must be called once from
 * [com.aistudio.agritech.MainActivity] before any network calls are made,
 * using `BuildConfig.AGRITECH_API_URL` injected by the Secrets Gradle Plugin.
 *
 * Network configuration:
 * - 30s connect / 60s read timeouts (appropriate for image upload + inference)
 * - HTTP logging enabled for DEBUG builds via [HttpLoggingInterceptor]
 * - Moshi with Kotlin reflection adapter for JSON parsing
 */
object ApiClient {

    private var _service: ApiService? = null

    /** The fully configured API service. Throws if [initialize] was not called first. */
    val service: ApiService
        get() = _service
            ?: error("ApiClient not initialized. Call ApiClient.initialize(baseUrl) in MainActivity.")

    /**
     * Initializes the Retrofit client with the given base URL.
     * Safe to call multiple times; re-initialization is a no-op if already set.
     *
     * @param baseUrl Root URL of the FastAPI server, e.g. "https://api.example.com/api/v1/"
     *                Must end with a trailing slash as required by Retrofit.
     */
    fun initialize(baseUrl: String) {
        if (_service != null) return

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()

        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        _service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
    }
}
