# OOJOO FARM Android

마스터/슬레이브 2개 모듈을 가진 멀티모듈 안드로이드 프로젝트.

## Android Studio에서 열기 (권장)
1. Android Studio 실행 → **Open** (New 아님)
2. `C:\ai_dev\WooJU_FARM\android` 폴더 선택 → OK
3. Gradle Sync 자동 실행 → SDK/의존성 다운로드 (최초 수분 소요)
4. Sync 완료 후 빌드: **Build → Make Project** (Ctrl+F9)

> 최초 오픈 시 "Gradle wrapper missing" 경고가 나면 **OK** 클릭 — Studio가 wrapper를 자동 생성합니다.

## 구조
```
android/
  settings.gradle.kts        # :master:app, :slave:app 포함
  build.gradle.kts           # 플러그인 선언
  gradle.properties
  gradle/wrapper/
  master/
    app/
      build.gradle.kts       # namespace com.oojoo.farm.master, minSdk 26
      src/main/AndroidManifest.xml
      src/main/java/com/oojoo/farm/master/...
  slave/
    app/
      build.gradle.kts       # namespace com.oojoo.farm.slave, minSdk 24
      src/main/AndroidManifest.xml
      src/main/java/com/oojoo/farm/slave/...
```

## 백엔드 주소
- 에뮬레이터에서 PC 로컬: `http://10.0.2.2:4000/` (기본값)
- 우분투 서버: 각 `ApiClient.kt`의 baseUrl 또는 슬레이브 PairingScreen에서 변경

## 실행 (Run)
- 상단 드롭다운에서 **app(master)** 또는 **app(slave)** 선택 → Run ▶
- 에뮬레이터: Device Manager → Pixel 7 (Galaxy S 화면 크기 유사) 권장
