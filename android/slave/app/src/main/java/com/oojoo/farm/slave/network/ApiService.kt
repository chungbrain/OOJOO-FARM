package com.oojoo.farm.slave.network

import com.oojoo.farm.slave.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface ApiService {
    @POST("api/pairing/verify")
    suspend fun pairVerify(@Body body: PairingVerifyRequest): PairingVerifyResponse

    @POST("api/pairing/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): OkResponse

    @POST("api/watering/log")
    suspend fun wateringLog(@Body body: WateringLogRequest): WateringLogIdResponse

    @POST("api/events")
    suspend fun reportEvent(@Body body: EventRequest): EventIdResponse

    @GET("api/plants/slave/{slaveId}")
    suspend fun slavePlants(@Path("slaveId") slaveId: String): PlantsResponse

    @GET("api/commands/pending/{slaveId}")
    suspend fun pendingCommands(@Path("slaveId") slaveId: String): CommandsResponse

    @POST("api/commands/{id}/done")
    suspend fun commandDone(@Path("id") id: String): OkResponse

    @GET("api/policy/{slaveId}")
    suspend fun policy(@Path("slaveId") slaveId: String): PolicyResponse

    @GET("api/weather/{region}")
    suspend fun weather(@Path("region") region: String): WeatherResponse

    @POST("api/videos/upload/{slaveId}")
    @Multipart
    suspend fun uploadVideo(
        @Path("slaveId") slaveId: String,
        @Part video: MultipartBody.Part,
        @Part("commandId") commandId: RequestBody?
    ): VideoUploadResponse

    // 10분 세션 분석 보고
    @POST("api/analysis/report")
    suspend fun reportAnalysis(@Body body: AnalysisReportRequest): OkResponse
}
