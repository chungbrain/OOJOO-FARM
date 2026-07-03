# OOJOO FARM - Master 앱 (Phase 1)

## 프로젝트 생성
1. Android Studio → **New Project → Empty Activity (Compose)**
   - Language: Kotlin, Minimum SDK: API 26
   - Package name: `com.oojoo.farm.master`
2. 생성 후 `app/src/main/java/com/oojoo/farm/master/` 아래 파일들을 복사
   - `MainActivity.kt`
   - `model/Models.kt`
   - `network/ApiService.kt`, `network/ApiClient.kt`
   - `ui/PairingScreen.kt`, `ui/HomeScreen.kt`

## app/build.gradle.kts
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.oojoo.farm.master"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.oojoo.farm.master"
        minSdk = 26
        targetSdk = 34
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
}
```

## project/build.gradle.kts (plugins 블록)
```kotlin
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
```

## AndroidManifest.xml
`<manifest>` 안에 권한 추가, `<application>`에 cleartext(http) 허용:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<application
    android:usesCleartextTraffic="true" ... >
```

## 백엔드 주소
- `network/ApiClient.kt`의 `baseUrl` 변경 → `http://<우분투서버IP>:4000/`
- 에뮬레이터에서 PC 로컬 백엔드: `http://10.0.2.2:4000/`

## 실행 흐름
Run ▶ → 홈(우측상단 "Farmer 연결") → 사용자 ID 입력 → 코드 생성 → **Farmer(슬레이브) 앱**에서 코드 입력
