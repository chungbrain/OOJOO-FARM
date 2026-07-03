package com.oojoo.farm.slave.model

import kotlinx.serialization.Serializable

@Serializable
data class PairingVerifyRequest(val code: String)
@Serializable
data class PairingVerifyResponse(val slaveId: String, val sessionKey: String, val userId: String)
@Serializable
data class HeartbeatRequest(val slaveId: String)
@Serializable
data class OkResponse(val ok: Boolean)

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
