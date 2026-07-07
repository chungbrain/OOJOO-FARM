package com.oojoo.farm.slave.model

import kotlinx.serialization.Serializable

@Serializable
data class PairingVerifyRequest(val code: String, val name: String? = null)
@Serializable
data class PairingVerifyResponse(val slaveId: String, val sessionKey: String, val userId: String, val name: String? = null)
@Serializable
data class HeartbeatRequest(val slaveId: String, val battery: Int? = null)
@Serializable
data class OkResponse(val ok: Boolean)

@Serializable
data class PolicyResponse(
    val slaveId: String,
    val water_auto: Int = 1,
    val fan_auto: Int = 1,
    val laser_approval: Int = 1,
    val capture_interval: Int = 60,
    val region: String? = null
)

@Serializable
data class WateringLogRequest(
    val slaveId: String,
    val plantId: String? = null,
    val amountMl: Int = 300,
    val source: String = "auto",
    val weatherFactor: Double = 1.0
)
@Serializable
data class WateringLogIdResponse(val logId: String)

@Serializable
data class EventRequest(
    val slaveId: String,
    val plantId: String? = null,
    val type: String,
    val payload: Map<String, String> = emptyMap()
)
@Serializable
data class EventIdResponse(val eventId: String)

@Serializable
data class Plant(
    val id: String,
    val user_id: String? = null,
    val slave_id: String? = null,
    val name: String,
    val species: String? = null,
    val planted_at: String? = null,
    val stage: String? = null
)
@Serializable
data class PlantsResponse(val plants: List<Plant>)

@Serializable
data class Command(
    val id: String,
    val slave_id: String,
    val plant_id: String? = null,
    val action: String,
    val amount_ml: Int = 300,
    val weather_factor: Double = 1.0,
    val status: String = "queued",
    val created_at: String? = null,
    val executed_at: String? = null
)
@Serializable
data class CommandsResponse(val commands: List<Command>)

@Serializable
data class WeatherResponse(
    val region: String,
    val temp: Double? = null,
    val humidity: Double? = null,
    val precipitation: Double? = null,
    val weatherCode: Int? = null,
    val weatherFactor: Double = 1.0
)

@Serializable
data class VideoUploadResponse(
    val videoId: String,
    val url: String
)
