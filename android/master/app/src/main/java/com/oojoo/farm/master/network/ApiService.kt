package com.oojoo.farm.master.network

import com.oojoo.farm.master.model.*
import retrofit2.http.*

interface ApiService {
    @POST("api/users")
    suspend fun createUser(@Body body: UserRequest): User

    @GET("api/users/{id}")
    suspend fun user(@Path("id") id: String): User

    @POST("api/pairing/code")
    suspend fun pairCode(@Body body: PairingCodeRequest): PairingCodeResponse

    @POST("api/pairing/verify")
    suspend fun pairVerify(@Body body: PairingVerifyRequest): PairingVerifyResponse

    @GET("api/pairing/{userId}")
    suspend fun slaves(@Path("userId") userId: String): SlavesResponse

    @POST("api/pairing/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest): OkResponse

    @DELETE("api/pairing/{slaveId}")
    suspend fun unpair(@Path("slaveId") slaveId: String): OkResponse

    @GET("api/policy/{slaveId}")
    suspend fun policy(@Path("slaveId") slaveId: String): PolicyResponse

    @PUT("api/policy/{slaveId}")
    suspend fun setPolicy(@Path("slaveId") slaveId: String, @Body body: PolicyRequest): PolicyResponse

    @GET("api/plants/{userId}")
    suspend fun plants(@Path("userId") userId: String): PlantsResponse

    @POST("api/plants")
    suspend fun createPlant(@Body body: CreatePlantRequest): PlantIdResponse

    @PUT("api/plants/plant/{id}")
    suspend fun updatePlant(@Path("id") id: String, @Body body: UpdatePlantRequest): OkResponse

    @DELETE("api/plants/plant/{id}")
    suspend fun deletePlant(@Path("id") id: String): OkResponse

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

    @POST("api/commands")
    suspend fun sendCommand(@Body body: CommandRequest): CommandResponse

    @GET("api/commands/history/{slaveId}")
    suspend fun commandHistory(@Path("slaveId") slaveId: String): CommandsResponse

    @GET("api/notifications/{userId}")
    suspend fun notifications(@Path("userId") userId: String): NotificationsResponse

    // ---------- 마켓플레이스 ----------
    @GET("api/market/categories")
    suspend fun marketCategories(): CategoriesResponse

    @GET("api/market/products")
    suspend fun marketProducts(
        @Query("category") category: String? = null,
        @Query("q") q: String? = null,
        @Query("sort") sort: String? = null
    ): ProductsResponse

    @GET("api/market/products/{id}")
    suspend fun marketProduct(@Path("id") id: String): Product

    @GET("api/market/bundles")
    suspend fun marketBundles(): BundlesResponse

    @GET("api/market/recommendations/{userId}")
    suspend fun marketRecommendations(@Path("userId") userId: String): RecommendationsResponse

    @POST("api/market/affiliate/{id}")
    suspend fun marketAffiliate(@Path("id") id: String, @Body body: AffiliateRequest): AffiliateResponse

    @POST("api/market/orders")
    suspend fun marketCreateOrder(@Body body: CreateOrderRequest): OrderResponse

    @GET("api/market/orders/{userId}")
    suspend fun marketOrders(@Path("userId") userId: String): OrdersResponse

    // ---------- 커뮤니티 ----------
    @GET("api/community/posts")
    suspend fun communityPosts(
        @Query("region") region: String? = null,
        @Query("type") type: String? = null,
        @Query("q") q: String? = null,
        @Query("viewerId") viewerId: String? = null
    ): CommunityPostsResponse

    @GET("api/community/posts/{id}")
    suspend fun communityPost(@Path("id") id: String): CommunityPostDetail

    @POST("api/community/posts")
    suspend fun communityCreate(@Body body: CreatePostRequest): PostIdResponse

    @POST("api/community/posts/{id}/comments")
    suspend fun communityComment(@Path("id") id: String, @Body body: CommentRequest): CommentIdResponse

    @PATCH("api/community/posts/{id}/status")
    suspend fun communityStatus(@Path("id") id: String, @Body body: StatusRequest): OkResponse

    @POST("api/community/report")
    suspend fun communityReport(@Body body: ReportRequest): OkResponse

    @POST("api/community/block")
    suspend fun communityBlock(@Body body: BlockRequest): OkResponse

    // ---------- 리포트 / 구독 ----------
    @GET("api/report/{slaveId}")
    suspend fun report(@Path("slaveId") slaveId: String): ReportResponse

    @GET("api/subscription/plans")
    suspend fun subscriptionPlans(): PlansResponse

    @GET("api/subscription/{userId}")
    suspend fun subscription(@Path("userId") userId: String): SubscriptionResponse

    @POST("api/subscription")
    suspend fun subscribe(@Body body: SubscribeRequest): SubscriptionResponse

    @GET("api/weather/{region}")
    suspend fun weather(@Path("region") region: String): WeatherResponse

    @GET("api/weather/coords")
    suspend fun weatherByCoords(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): WeatherResponse

    // ---------- 비디오 캡처 ----------
    @GET("api/videos/by-command/{commandId}")
    suspend fun videoByCommand(@Path("commandId") commandId: String): VideoInfoResponse

    // ---------- Farmer 분석 결과 ----------
    @GET("api/analysis/latest/{plantId}")
    suspend fun latestAnalysis(@Path("plantId") plantId: String): AnalysisResponse

    @GET("api/analysis/history/{plantId}")
    suspend fun analysisHistory(@Path("plantId") plantId: String): AnalysisHistoryResponse
}
