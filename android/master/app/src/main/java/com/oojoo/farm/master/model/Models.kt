package com.oojoo.farm.master.model

import kotlinx.serialization.Serializable

@Serializable
data class UserRequest(val id: String? = null, val nickname: String? = null, val region: String? = null)
@Serializable
data class User(
    val id: String,
    val nickname: String? = null,
    val region: String? = null,
    val created_at: String? = null
)

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
    val last_seen: String? = null,
    val battery: Int? = null
)
@Serializable
data class SlavesResponse(val slaves: List<Slave>)

@Serializable
data class PolicyRequest(
    val waterAuto: Boolean? = null,
    val fanAuto: Boolean? = null,
    val laserApproval: Boolean? = null,
    val captureInterval: Int? = null,
    val region: String? = null
)
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
data class Notification(
    val id: String,
    val type: String,
    val payload: String? = null,
    val created_at: String? = null,
    val slave_id: String? = null,
    val slave_name: String? = null,
    val plant_id: String? = null,
    val plant_name: String? = null
)
@Serializable
data class NotificationsResponse(val notifications: List<Notification>)

// ---------- 마켓플레이스 ----------
@Serializable
data class Product(
    val id: String,
    val category: String? = null,
    val name: String,
    val description: String? = null,
    val price: Int = 0,
    val vendor: String? = null,
    val image: String? = null,
    val affiliate_url: String? = null,
    val stock: Int = 0,
    val rating: Double = 0.0,
    val tags: String? = null
)
@Serializable
data class ProductsResponse(val products: List<Product>)
@Serializable
data class RecommendationsResponse(val recommendations: List<Product>)

@Serializable
data class MarketCategory(val key: String, val label: String, val count: Int = 0)
@Serializable
data class CategoriesResponse(val categories: List<MarketCategory>)

@Serializable
data class BundleItem(val id: String, val name: String, val price: Int = 0, val image: String? = null)
@Serializable
data class Bundle(
    val id: String,
    val name: String,
    val description: String? = null,
    val price: Int = 0,
    val image: String? = null,
    val items: List<BundleItem> = emptyList()
)
@Serializable
data class BundlesResponse(val bundles: List<Bundle>)

@Serializable
data class AffiliateRequest(val userId: String? = null)
@Serializable
data class AffiliateResponse(val url: String)

@Serializable
data class OrderItemRequest(val productId: String, val qty: Int = 1)
@Serializable
data class CreateOrderRequest(val userId: String, val items: List<OrderItemRequest>)
@Serializable
data class OrderResponse(val orderId: String, val total: Int, val status: String, val itemCount: Int = 0)
@Serializable
data class OrderItem(val product_id: String? = null, val name: String? = null, val qty: Int = 0, val price: Int = 0)
@Serializable
data class Order(
    val id: String,
    val user_id: String? = null,
    val total: Int = 0,
    val status: String? = null,
    val created_at: String? = null,
    val items: List<OrderItem> = emptyList()
)
@Serializable
data class OrdersResponse(val orders: List<Order>)

// ---------- 커뮤니티 ----------
@Serializable
data class CommunityPost(
    val id: String,
    val user_id: String? = null,
    val type: String,                 // share | sell | buy
    val title: String,
    val crop: String? = null,
    val quantity: String? = null,
    val price: Int? = null,
    val region: String? = null,
    val description: String? = null,
    val image: String? = null,
    val status: String? = "open",
    val created_at: String? = null,
    val author_name: String? = null,
    val author_score: Double = 0.0,
    val author_deals: Int = 0
)
@Serializable
data class CommunityPostsResponse(val posts: List<CommunityPost>)
@Serializable
data class CommunityComment(
    val id: String,
    val post_id: String? = null,
    val user_id: String? = null,
    val body: String,
    val created_at: String? = null,
    val author_name: String? = null
)
@Serializable
data class CommunityPostDetail(val post: CommunityPost, val comments: List<CommunityComment> = emptyList())
@Serializable
data class CreatePostRequest(
    val userId: String,
    val type: String,
    val title: String,
    val crop: String? = null,
    val quantity: String? = null,
    val price: Int? = null,
    val region: String? = null,
    val description: String? = null,
    val image: String? = null
)
@Serializable
data class PostIdResponse(val postId: String)
@Serializable
data class CommentRequest(val userId: String, val body: String)
@Serializable
data class CommentIdResponse(val commentId: String)
@Serializable
data class StatusRequest(val status: String)
@Serializable
data class ReportRequest(val reporterId: String, val postId: String? = null, val targetUserId: String? = null, val reason: String? = null)
@Serializable
data class BlockRequest(val blockerId: String, val blockedId: String)

// ---------- 리포트 ----------
@Serializable
data class WateringSummary(val count: Int = 0, val totalMl: Int = 0, val autoCount: Int = 0, val manualCount: Int = 0)
@Serializable
data class LastWatering(val created_at: String? = null, val amount_ml: Int = 0)
@Serializable
data class ReportResponse(
    val slaveId: String,
    val periodDays: Int = 7,
    val watering: WateringSummary = WateringSummary(),
    val harvestReady: Int = 0,
    val pestDetected: Int = 0,
    val anomalies: Int = 0,
    val lastWatering: LastWatering? = null
)

// ---------- 구독 ----------
@Serializable
data class SubscriptionResponse(
    val plan: String = "free",
    val name: String = "무료",
    val maxFarmers: Int = 2,
    val detailedReport: Boolean = false,
    val priorityCs: Boolean = false,
    val price: Int = 0
)
@Serializable
data class PlansResponse(val plans: List<SubscriptionResponse>)
@Serializable
data class SubscribeRequest(val userId: String, val plan: String)

@Serializable
data class WeatherResponse(
    val region: String,
    val temp: Double? = null,
    val humidity: Double? = null,
    val precipitation: Double? = null,
    val weatherCode: Int? = null,
    val weatherFactor: Double = 1.0,
    val label: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val cached: Boolean? = null
)

@Serializable
data class VideoInfoResponse(
    val videoId: String,
    val slaveId: String,
    val commandId: String? = null,
    val url: String,
    val mime: String? = null,
    val size: Int = 0,
    val created_at: String? = null
)
