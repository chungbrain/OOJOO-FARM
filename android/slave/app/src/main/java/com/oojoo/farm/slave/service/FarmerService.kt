package com.oojoo.farm.slave.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.oojoo.farm.slave.MainActivity
import com.oojoo.farm.slave.R
import com.oojoo.farm.slave.data.Prefs
import com.oojoo.farm.slave.network.ApiClient
import com.oojoo.farm.slave.vision.CameraHost

/**
 * 헤드리스 자율 관리용 Foreground Service.
 *
 * 화면이 꺼져도(또는 앱이 백그라운드여도) 자율 루프가 지속되도록 프로세스를 유지하고,
 * PARTIAL_WAKE_LOCK 으로 CPU 절전을 막는다. FarmerEngine 을 구동/유지한다.
 * PRD 4.2 / 7.2 (헤드리스·상시 동작·오프라인 자율 유지) 요구사항 대응.
 */
class FarmerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        acquireWakeLock()
        if (intent?.action == ACTION_RECONNECT) {
            FarmerEngine.stop()
            ApiClient.setBaseUrl(Prefs.serverUrl(this))
            ApiClient.setSessionKey(Prefs.sessionKey(this))
        }
        if (Prefs.isPaired(this)) {
            FarmerEngine.start(applicationContext)
            // 카메라는 서비스 라이프사이클에 바인딩 — 앱이 내려가도 관찰·촬영 유지.
            // (FGS camera 타입이 활성화되지 않은 백그라운드 시작이면 바인딩이
            //  실패하고, 사용자가 앱을 열어 재호출할 때 재시도된다.)
            CameraHost.start(applicationContext)
        }
        // 시스템이 종료해도 재시작되도록 STICKY (24h+ 자율 유지)
        return START_STICKY
    }

    override fun onDestroy() {
        FarmerEngine.stop()
        CameraHost.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        when {
            // Android 11+: 백그라운드 카메라 접근을 위해 camera 타입 포함.
            // 앱이 화면에 보이는 동안 시작된 경우에만 허용되므로 실패 시 dataSync로 fallback.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                try {
                    startForeground(
                        NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                } catch (_: Exception) {
                    try {
                        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } catch (_: Exception) {
                        startForeground(NOTIF_ID, notification)
                    }
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OojooFarmer::EngineWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.service_channel_desc) }
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "farmer_service"
        private const val NOTIF_ID = 1001
        private const val ACTION_RECONNECT = "com.oojoo.farm.slave.action.RECONNECT"

        fun start(ctx: Context) {
            val intent = Intent(ctx, FarmerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FarmerService::class.java))
        }

        fun reconnect(ctx: Context) {
            val intent = Intent(ctx, FarmerService::class.java).setAction(ACTION_RECONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
