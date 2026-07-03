package com.oojoo.farm.slave.network

import com.oojoo.farm.slave.model.*
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
}
