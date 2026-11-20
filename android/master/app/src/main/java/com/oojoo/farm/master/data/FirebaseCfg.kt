package com.oojoo.farm.master.data

/**
 * FCM (Firebase Cloud Messaging) 설정 — google-services.json 의 값을 여기 넣으면 푸시가 켜진다.
 *
 * 값이 비어 있는 동안 FCM 은 완전히 비활성: 토큰 요청도, 네트워크 호출도 없다.
 * (google-services.json 없이도 앱이 빌드·동작하는 것이 의도 — 설정만 넣으면 즉시 유효.)
 *
 * 값 얻기:
 *  1. Firebase Console → 프로젝트 생성
 *  2. Android 앱 추가 (패키지명 com.oojoo.farm.master)
 *  3. google-services.json 다운로드 후 아래 3개 값 복사
 *
 * 백엔드에도 대응 설정이 필요: FCM_PROJECT_ID / FCM_SERVICE_ACCOUNT (env).
 */
object FirebaseCfg {
    const val PROJECT_ID: String = ""   // project_info.project_id
    const val API_KEY: String = ""      // client_info.api_key
    const val APP_ID: String = ""       // client_info.mobilesdk_app_id ("1:…:android:…" 형태)

    val configured: Boolean
        get() = PROJECT_ID.isNotBlank() && API_KEY.isNotBlank() && APP_ID.isNotBlank()
}
