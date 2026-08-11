package com.oojoo.farm.master.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClient {
    @Volatile
    // 에뮬레이터에서 로컬 백엔드: http://10.0.2.2:4000/
    // 실제 우분투 서버: http://<서버IP>:4000/
    var baseUrl: String = "http://10.0.2.2:4000/"
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    private fun build(url: String = baseUrl): Retrofit = Retrofit.Builder()
        .baseUrl(url)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Volatile
    private var cached: ApiService? = null
    val api: ApiService
        get() = cached ?: synchronized(this) {
            cached ?: build().create(ApiService::class.java).also { cached = it }
        }

    fun setBaseUrl(url: String): Boolean {
        val normalized = (validateServerEndpoint(url) as? ServerEndpointValidation.Valid)?.normalizedUrl
            ?: return false
        if (normalized != baseUrl) {
            baseUrl = normalized
            cached = null // 재생성 유도
        }
        return true
    }

    suspend fun verifyBaseUrl(url: String): Boolean {
        val normalized = (validateServerEndpoint(url) as? ServerEndpointValidation.Valid)?.normalizedUrl
            ?: return false
        return try {
            build(normalized).create(ApiService::class.java).health().ok
        } catch (_: Exception) {
            false
        }
    }
}
