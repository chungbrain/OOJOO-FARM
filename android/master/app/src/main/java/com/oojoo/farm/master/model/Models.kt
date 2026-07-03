package com.oojoo.farm.master.model

import kotlinx.serialization.Serializable

@Serializable
data class PairingCodeRequest(val userId: String)
@Serializable
data class PairingCodeResponse(val code: String, val expiresAt: String)
@Serializable
data class PairingVerifyRequest(val code: String)
@Serializable
data class PairingVerifyResponse(val slaveId: String, val sessionKey: String, val userId: String)
@Serializable
data class HeartbeatRequest(val slaveId: String)
@Serializable
data class OkResponse(val ok: Boolean)

@Serializable
data class Slave(
    val id: String,
    val name: String,
    val online: Int,
    val last_seen: String? = null
)
@Serializable
data class SlavesResponse(val slaves: List<Slave>)

@Serializable
data class CreatePlantRequest(
    val userId: String,
    val slaveId: String? = null,
    val name: String,
    val species: String? = null,
    val plantedAt: String? = null,
    val stage: String = "seedling"
)
@Serializable
data class PlantIdResponse(val plantId: String)
@Serializable
data class PlantsResponse(val plants: List<Plant>)
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
data class EventRequest(
    val slaveId: String,
    val plantId: String? = null,
    val type: String,
    val payload: Map<String, String> = emptyMap()
)
@Serializable
data class EventIdResponse(val eventId: String)
@Serializable
data class EventsResponse(val events: List<FarmEvent>)
@Serializable
data class FarmEvent(
    val id: String,
    val slave_id: String,
    val plant_id: String? = null,
    val type: String,
    val payload: String? = null,
    val created_at: String? = null
)

@Serializable
data class WateringCommandRequest(
    val slaveId: String,
    val plantId: String? = null,
    val amountMl: Int = 300,
    val weatherFactor: Double = 1.0
)
@Serializable
data class WateringCommandResponse(
    val commandId: String,
    val slaveId: String,
    val amountMl: Int,
    val weatherFactor: Double,
    val status: String
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
data class WateringsResponse(val waterings: List<Watering>)
@Serializable
data class Watering(
    val id: String,
    val slave_id: String,
    val plant_id: String? = null,
    val amount_ml: Int,
    val source: String,
    val weather_factor: Double,
    val created_at: String? = null
)

@Serializable
data class CommandRequest(
    val slaveId: String,
    val plantId: String? = null,
    val action: String = "water",
    val amountMl: Int = 300,
    val weatherFactor: Double = 1.0
)
@Serializable
data class CommandResponse(
    val commandId: String,
    val status: String
)
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
