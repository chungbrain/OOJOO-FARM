package com.oojoo.farm.master.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.oojoo.farm.master.MainActivity
import com.oojoo.farm.master.R
import com.oojoo.farm.master.data.FirebaseCfg
import com.oojoo.farm.master.data.Prefs
import com.oojoo.farm.master.data.Session
import com.oojoo.farm.master.data.AppStrings
import com.oojoo.farm.master.model.PushTokenRequest
import com.oojoo.farm.master.network.ApiClient
import com.oojoo.farm.master.ui.notiColor
import com.oojoo.farm.master.ui.notiLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * FCM 수신 서비스 — data 메시지만 받는다 (FCM notification 필드 미사용).
 * 메시지 data.type 으로 마스터 앱의 기존 알림 라벨/색 매핑(notiLabel/notiColor)을
 * 그대로 재용해 로컬 알림을 렌더링한다.
 *
 * FirebaseCfg 가 비어 있으면 (google-services.json 미적용) FCM SDK 가 초기화되지
 * 않아 이 서비스도 호출되지 않는다 — 설정이 켜지는 순간부터 동작한다.
 */
class FcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 토큰 관리 ----

    override fun onNewToken(token: String) {
        if (!FirebaseCfg.configured) return
        val userId = Session.userId
        if (userId.isBlank()) {
            // 로그인 전 토큰 — 일시 보관, 로그인 후 bootstrap 에서 등록.
            Prefs.setPendingPushToken(applicationContext, token)
            return
        }
        registerToken(userId, token)
    }

    private fun registerToken(userId: String, token: String) {
        scope.launch {
            try {
                ApiClient.api.registerPushToken(userId, PushTokenRequest(token))
                Log.i(TAG, "FCM 토큰 등록 완료 (user …${userId.takeLast(4)})")
            } catch (e: Exception) {
                Log.w(TAG, "FCM 토큰 등록 실패: ${e.message}")
            }
        }
    }

    // ---- 메시지 → 로컬 알림 ----

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"] ?: return
        val payload = message.data["payload"]
        ensureChannel()
        val strings = AppStrings(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            (System.currentTimeMillis() / 1000).toInt(),
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notiLabel(type, strings))
                .setContentText(summaryFor(type, payload))
                .setStyle(NotificationCompat.BigTextStyle().bigText(detailText(type, payload)))
                .setColor(notiColor(type, payload).argbInt())
                .setAutoCancel(true)
                .setContentIntent(contentIntent())
                .build()
        )
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OOJOO FARM 알림", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        private const val TAG = "FcmService"
        private const val CHANNEL_ID = "oojoo_alerts"

        /**
         * 로그인/기동 시 호출 — FCM 초기화 후 토큰 확보 + (로그인 전 보관된) 대기 토큰 등록.
         * FirebaseCfg 비활성 시 아무 일도 하지 않는다.
         */
        fun bootstrap(ctx: Context, userId: String) {
            if (!FirebaseCfg.configured) return
            if (userId.isBlank()) return
            val app = ctx.applicationContext
            val pending = Prefs.pendingPushToken(app)
            if (pending != null) {
                Prefs.clearPendingPushToken(app)
                registerNow(app, userId, pending)
            }
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val token = FirebaseMessaging.getInstance().token.await()
                    if (!token.isNullOrBlank()) registerNow(app, userId, token)
                } catch (e: Exception) {
                    Log.w(TAG, "FCM 토큰 확보 실패: ${e.message}")
                }
            }
        }

        private fun registerNow(app: Context, userId: String, token: String) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    ApiClient.api.registerPushToken(userId, PushTokenRequest(token))
                } catch (_: Exception) {}
            }
        }
    }
}

// ---- 페이로드 텍스트 (data 만 실어 보내므로 클라이언트가 요약 생성) ----
private fun FcmService.summaryFor(type: String, payload: String?): String {
    val strings = AppStrings(applicationContext)
    return when (type) {
        "harvest_ready" -> strings.fcmHarvestReady
        "pest_detected" -> strings.fcmPestDetected
        "water_low" -> strings.fcmWaterLow
        "health_change" -> strings.fcmHealthChange
        "water_failed" -> strings.fcmWaterFailed
        "battery_low" -> payload?.let {
            try {
                val level = org.json.JSONObject(it).optInt("level", -1)
                if (level >= 0) strings.fcmBatteryLow(level) else strings.fcmBatteryLow(0)
            } catch (_: Exception) { strings.fcmBatteryLow(0) }
        } ?: strings.fcmBatteryLow(0)
        else -> notiLabel(type, strings)
    }
}

private fun FcmService.detailText(type: String, payload: String?): String {
    val slaveId = try { org.json.JSONObject(payload ?: "{}").optString("slaveId", "") } catch (_: Exception) { "" }
    return if (slaveId.isBlank()) "" else "Farmer: $slaveId"
}

// Compose 1.6 Color(value class) 에 toArgb() 이 없어 수동 변환.
private fun androidx.compose.ui.graphics.Color.argbInt(): Int {
    val a = (alpha * 255f).roundToInt()
    val r = (red * 255f).roundToInt()
    val g = (green * 255f).roundToInt()
    val b = (blue * 255f).roundToInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
