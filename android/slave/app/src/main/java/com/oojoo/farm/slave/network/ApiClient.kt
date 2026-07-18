package com.oojoo.farm.slave.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClient {
    var baseUrl: String = "http://10.0.2.2:4000/"
        private set

    // 페어링 후 발급받은 세션키. 모든 요청에 x-session-key 헤더로 첨부된다.
    @Volatile
    private var sessionKey: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                sessionKey?.let { builder.addHeader("x-session-key", it) }
                chain.proceed(builder.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    private fun build(): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    // baseUrl 이 바뀔 수 있으므로 lazy 대신 캐시 후 재생성.
    @Volatile
    private var cached: ApiService? = null
    val api: ApiService
        get() = cached ?: synchronized(this) {
            cached ?: build().create(ApiService::class.java).also { cached = it }
        }

    fun setBaseUrl(url: String) {
        val normalized = if (url.endsWith("/")) url else "$url/"
        if (normalized != baseUrl) {
            baseUrl = normalized
            cached = null // 재생성 유도
        }
    }

    fun setSessionKey(key: String?) {
        sessionKey = key
    }
}
