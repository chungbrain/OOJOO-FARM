package com.oojoo.farm.master.network

import com.oojoo.farm.master.model.*
import retrofit2.http.*

interface ApiService {
    @POST("api/pairing/code")
    suspend fun pairCode(@Body body: PairingCodeRequest): PairingCodeResponse

    @POST("api/pairing/verify")
    suspend fun pairVerify(@Body body: PairingVerifyRequest): PairingVerifyResponse

    @GET("api/pairing/{userId}")
    suspend fun slaves(@Path("userId") userId: String): SlavesResponse

    @POST("api/pairing/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): OkResponse

    @GET("api/plants/{userId}")
    suspend fun plants(@Path("userId") userId: String): PlantsResponse

    @POST("api/plants")
    suspend fun createPlant(@Body body: CreatePlantRequest): PlantIdResponse

    @GET("api/plants/plant/{id}")
    suspend fun plant(@Path("id") id: String): Plant

    @POST("api/events")
    suspend fun reportEvent(@Body body: EventRequest): EventIdResponse

    @GET("api/events/{slaveId}")
    suspend fun events(@Path("slaveId") slaveId: String): EventsResponse

    @POST("api/watering/command")
    suspend fun wateringCommand(@Body body: WateringCommandRequest): WateringCommandResponse

    @POST("api/watering/log")
    suspend fun wateringLog(@Body body: WateringLogRequest): WateringLogIdResponse

    @GET("api/watering/{plantId}")
    suspend fun waterings(@Path("plantId") plantId: String): WateringsResponse
}
