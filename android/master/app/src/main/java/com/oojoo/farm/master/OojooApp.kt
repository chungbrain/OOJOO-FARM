package com.oojoo.farm.master

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.oojoo.farm.master.data.AppLocale
import com.oojoo.farm.master.data.FirebaseCfg

/**
 * 앱 진입점 — 로케일 적용 + (설정 시) Firebase 초기화.
 *
 * Firebase 는 google-services 플러그인 없이 수동 초기화한다:
 * FirebaseCfg 에 값이 채워지기 전까지는 FirebaseApp 이 존재하지 않아
 * FcmService 도, 토큰 요청도 발생하지 않는다.
 */
class OojooApp : Application() {
    override fun onCreate() {
        // 로케일 먼저 — FcmService(알림 문자열) 가 액티비티 없이도 올바른 언어를 쓴다.
        AppLocale.initialize(this)
        super.onCreate()
        if (FirebaseCfg.configured && FirebaseApp.getApps(this).isEmpty()) {
            try {
                val options = FirebaseOptions.Builder()
                    .setApiKey(FirebaseCfg.API_KEY)
                    .setApplicationId(FirebaseCfg.APP_ID)
                    .setProjectId(FirebaseCfg.PROJECT_ID)
                    .build()
                FirebaseApp.initializeApp(this, options)
                Log.i("OojooApp", "Firebase init 완료 (FCM on, project=${FirebaseCfg.PROJECT_ID})")
            } catch (e: Exception) {
                Log.w("OojooApp", "Firebase init 실패 — FCM off: ${e.message}")
            }
        }
    }
}
