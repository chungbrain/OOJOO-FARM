package com.oojoo.farm.slave.network

import com.oojoo.farm.slave.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface ApiService {
    @GET("health")
    suspend fun health(): HealthResponse

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

    // 로컬 ROI 편집 결과 반영 (마스터의 설정은 5분 주기 재조회로 동기화)
    @PUT("api/plants/plant/{id}")
    suspend fun updatePlant(@Path("id") id: String, @Body body: UpdatePlantRequest): OkResponse

    @GET("api/commands/pending/{slaveId}")
    suspend fun pendingCommands(@Path("slaveId") slaveId: String): CommandsResponse

    @POST("api/commands/{id}/done")
    suspend fun commandDone(@Path("id") id: String): OkResponse

    @GET("api/policy/{slaveId}")
    suspend fun policy(@Path("slaveId") slaveId: String): PolicyResponse

    @POST("api/analysis/report")
    suspend fun reportAnalysis(@Body body: AnalysisReportRequest): AnalysisReportResponse

    @GET("api/weather/{region}")
    suspend fun weather(@Path("region") region: String): WeatherResponse

    @GET("api/weather/coords")
    suspend fun weatherByCoords(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): WeatherResponse

    @Multipart
    @POST("api/videos/upload/{slaveId}")
    suspend fun uploadVideo(
        @Path("slaveId") slaveId: String,
        @Part video: MultipartBody.Part,
        @Part("commandId") commandId: RequestBody? = null,
        @Part("plantId") plantId: RequestBody? = null,
        @Part("kind") kind: RequestBody? = null,
        @Part("photoCount") photoCount: RequestBody? = null
    ): VideoUploadResponse

    @Multipart
    @POST("api/photos/upload/{slaveId}")
    suspend fun uploadPhoto(
        @Path("slaveId") slaveId: String,
        @Part photo: MultipartBody.Part,
        @Part thumb: MultipartBody.Part? = null,
        @Part("plantId") plantId: RequestBody,
        @Part("takenAt") takenAt: RequestBody,
        @Part("location") location: RequestBody,
        @Part("commandId") commandId: RequestBody? = null
    ): PhotoUploadResponse

    @GET("api/photos/plant/{plantId}")
    suspend fun plantPhotos(@Path("plantId") plantId: String): PlantPhotosResponse

    @POST("api/videos/clip-failed/{slaveId}")
    suspend fun clipFailed(@Path("slaveId") slaveId: String, @Body body: ClipFailedRequest): OkResponse

    // 실시간 보기 — 마스터의 live_start/live_stop 명령으로 세션을 열고
    // 3초마다 최신 카메라 프레임(JPEG)을 백엔드에 push한다.
    @Multipart
    @POST("api/live/{slaveId}/frame")
    suspend fun uploadLiveFrame(
        @Path("slaveId") slaveId: String,
        @Part frame: MultipartBody.Part
    ): OkResponse

    @Multipart
    @POST("api/diagnosis/sessions/{slaveId}")
    suspend fun uploadDiagnosisSession(
        @Path("slaveId") slaveId: String,
        @Part frames: List<MultipartBody.Part>,
        @Part("plantId") plantId: RequestBody,
        @Part("modelId") modelId: RequestBody? = null,
        @Part("aiLabel") aiLabel: RequestBody,
        @Part("aiConfidence") aiConfidence: RequestBody? = null,
        @Part("frameCount") frameCount: RequestBody? = null,
        @Part("videoId") videoId: RequestBody? = null,
        @Part("commandId") commandId: RequestBody? = null,
        @Part("framesMeta") framesMeta: RequestBody? = null
    ): DiagnosisSessionResponse
}
