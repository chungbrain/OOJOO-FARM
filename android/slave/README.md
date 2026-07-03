# OOJOO FARM - Slave(Farmer) 앱 (Phase 1)

## 프로젝트 생성
1. Android Studio → **New Project → Empty Activity (Compose)**
   - Language: Kotlin, Minimum SDK: API 24
   - Package name: `com.oojoo.farm.slave`
2. `app/src/main/java/com/oojoo/farm/slave/` 아래 파일 복사
   - `MainActivity.kt`
   - `data/Prefs.kt`
   - `model/Models.kt`
   - `network/ApiService.kt`, `network/ApiClient.kt`
   - `ui/PairingScreen.kt`, `ui/DashboardScreen.kt`

## 빌드 설정
마스터 앱의 `app/build.gradle.kts` 와 동일(네트워크/Compose 의존성). `namespace`/`applicationId`만 `com.oojoo.farm.slave`로 변경. `minSdk = 24`.

## AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
<application android:usesCleartextTraffic="true" ... >
```

## 백엔드 주소
- `PairingScreen`에서 서버 주소 입력(기본 `http://10.0.2.2:4000/` → 에뮬레이터 기준)
- 실제 우분투 서버: `http://<서버IP>:4000/`

## 실행 흐름
1. **마스터 앱**에서 "Farmer 연결" → 코드 생성
2. **슬레이브 앱** 시작 → 코드 입력 → 연결 → 대시보드
3. 대시보드: 30초마다 자율 루프(하트비트 + 가짜 AI 판정 → 자율 관수 → 로그/이벤트 전송)
4. "수동 관수" 버튼으로 즉시 실행

> Phase 1의 온디바이스 AI 판정은 가짜(무작위)입니다. Phase 2에서 실제 비전 모델(TFLite)로 교체 예정.
