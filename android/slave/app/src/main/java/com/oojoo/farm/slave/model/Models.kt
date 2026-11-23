package com.oojoo.farm.slave.model

import kotlinx.serialization.Serializable

@Serializable
data class PairingVerifyRequest(val code: String, val name: String? = null)
@Serializable
data class PairingVerifyResponse(val slaveId: String, val sessionKey: String, val userId: String, val name: String? = null)
@Serializable
data class HeartbeatRequest(
    val slaveId: String,
    val battery: Int? = null,
    val streamUrl: String? = null,
    // farmer(농장) 위치 — 마스터 홈의 농장 날씨 조회용 (best-effort, 권한 없으면 null)
    val lat: Double? = null,
    val lon: Double? = null,
    val region: String? = null
)
@Serializable
data class OkResponse(val ok: Boolean)
@Serializable
data class HealthResponse(val ok: Boolean)

@Serializable
data class PolicyResponse(
    val slaveId: String,
    val water_auto: Int = 1,
    val fan_auto: Int = 1,
    val laser_approval: Int = 1,
    val capture_interval: Int = 60,
    val pest_interval: Int = 15,
    val roi_interval: Int = 20,
    val region: String? = null,
    // F4 — 카메라 물리 설치 위치 기반 자동 가로/세로 촬영 (auto | landscape | portrait)
    val camera_mount: String? = "auto"
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
    val stage: String? = null,
    /** 마스터가 farmer 사진 위에 설정한 관심영역 — 정규화 {x,y,w,h} 0..1 */
    val roi: RoiBox? = null
)
@Serializable
data class PlantsResponse(val plants: List<Plant>)

/** 식물별 관심영역(RoI) — 마스터 설정, 프레임 정규화 좌표 0..1 (좌상단 기준). */
@Serializable
data class RoiBox(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double
)

/** 로컬 ROI 편집 후 마스터(백엔드)에 반영 — roi:null = 삭제. */
@Serializable
data class UpdatePlantRequest(val roi: RoiBox? = null)

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
    val url: String,
    val kind: String? = null
)

@Serializable
data class PhotoUploadResponse(
    val photoId: String,
    val url: String? = null
)

@Serializable
data class PlantPhoto(
    val id: String,
    val slave_id: String? = null,
    val plant_id: String? = null,
    val url: String,
    val taken_at: String? = null,
    val location: String? = null,
    val created_at: String? = null
)

@Serializable
data class PlantPhotosResponse(val photos: List<PlantPhoto> = emptyList())

@Serializable
data class ClipFailedRequest(
    val commandId: String? = null,
    val plantId: String? = null,
    val error: String? = null
)

@Serializable
data class AnalysisPayload(
    val greenness: Double = 0.0,
    val brightness: Double = 0.0,
    val healthStatus: String = "",
    val needWater: Boolean = false,
    val confidence: Double = 0.0,
    val fruitRipeness: Double = 0.0,
    val pestSuspected: Boolean = false,
    val modelId: String? = null,
    // F1 — 건강등급 5단계(매우건강/건강/보통/나쁨/매우나쁨) — health_grade.bin
    val healthGrade: String? = null,
    val healthGradeKo: String? = null,
    val gradeConfidence: Double = 0.0,
    // F2 — 해충발생 4단계(없음/경미/보통/심각) — pest.bin(별도 모델)
    val pestLevel: String? = null,
    val pestLevelKo: String? = null,
    val pestConfidence: Double = 0.0,
    val pestModelId: String? = null
)

@Serializable
data class AnalysisReportRequest(
    val slaveId: String,
    val plantId: String,
    val analysis: AnalysisPayload
)

@Serializable
data class AnalysisReportResponse(val analysisId: String)

@Serializable
data class DiagnosisSessionResponse(val sessionId: String)
