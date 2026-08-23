---
name: build-apk-dist
description: OOJOO-FARM 안드로이드 APK 빌드 후 dist 폴더에 출력하는 절차. 빌드/APK 생성 요청 시 사용.
---

# APK 빌드 → dist 출력

## 환경 (이 Windows PC 기준)

- JAVA_HOME: `C:\Program Files\Android\Android Studio\jbr`
- Gradle: `C:\OOJOO-FARM\android\gradlew.bat`
- 항상 `--no-daemon` 사용 (데몬 프로세스 종료 문제 회피)

## 절차

1. **컴파일 검증 (빠른 확인)**
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:Path = "$env:JAVA_HOME\bin;$env:Path"
   .\gradlew.bat :slave:app:compileDebugKotlin --console=plain --no-daemon
   ```
   - 모듈: `:slave:app` (Farmer), `:master:app` (마스터)

2. **APK 빌드**
   ```powershell
   .\gradlew.bat :slave:app:assembleDebug :master:app:assembleDebug --console=plain --no-daemon
   ```

3. **dist 복사 확인**
   - 자동 복사 훅이 `android/build.gradle.kts`에 있으나 동작하지 않는 경우가 있음
   - 미리보기: `C:\OOJOO-FARM\dist\`
     - `oojoo-farm-farmer-debug.apk` (slave)
     - `oojoo-farm-master-debug.apk` (master)
   - 타임스탬프가 갱신 안 됐으면 수동 복사:
     ```powershell
     Copy-Item "android\slave\app\build\outputs\apk\debug\app-debug.apk" "dist\oojoo-farm-farmer-debug.apk" -Force
     Copy-Item "android\master\app\build\outputs\apk\debug\app-debug.apk" "dist\oojoo-farm-master-debug.apk" -Force
     ```

4. **빌드 실패 시**
   - 로그를 파일로 남기고 파싱:
     ```powershell
     .\gradlew.bat ... 2>&1 | Out-File -FilePath "$env:LOCALAPPDATA\Temp\opencode\build.log" -Encoding utf8
     ```
   - PowerShell 오류 스트림에 줄바꿈으로 메시지가 잘리므로 `[System.IO.File]::ReadAllText()`로 원문 확인

## 컴파일 자주 나는 에러

- `isActive` → `coroutineContext.isActive` + `import kotlin.coroutines.coroutineContext`
- Compose `Rect` 헬퍼 충돌 → 명시적 left/top/right/bottom 사용
- `detectDragGestures` → `androidx.compose.foundation.gestures` import
- OojooTheme 색상은 Teal 계열만 있음 (GreenDark 없음 — TealDark 사용)
