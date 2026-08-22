# 작업 일지 — 2026-08-22

## 1. GCP 백엔드 배포 환경 구축 (commit 없음 — 인프라 작업)

- 프로젝트: `oojoo-farm` (GCP, 서울 리전 asia-northeast3-a)
- VM: `oojoo-backend` (e2-small, Ubuntu 24.04, Node v22.23.2)
- 고정 IP: `34.50.40.104` 할당, 방화벽 TCP 4000 오픈 (`allow-oojoo-4000`)
- systemd 서비스 `oojoo.service` 등록 — 자동 시작/재시작
- 소스: `/opt/oojoo/repo` (GitHub clone)
- 접속 주소: `http://34.50.40.104:4000/`

## 2. 백그라운드 상시 동작 + 마스터 사진 즉시 촬영 (commit `0a0d51c`)

**문제:**
- 카메라가 Activity lifecycle에 바인딩되어 앱이 내려가면 촬영 불가
- FGS 타입이 `dataSync`뿐이라 Android 11+ 백그라운드 카메라 접근 불가
- 즉시 사진 촬영 명령(`capture_photo`) 없음 (영상만 존재)

**변경 내용 (12 files, +534 −189):**

| 파일 | 내용 |
|---|---|
| `android/slave/.../vision/CameraHost.kt` (신규) | 서비스 소유 카메라 라이프사이클 — 항상 RESUMED인 별도 LifecycleOwner에 CameraX 바인딩. UI는 surface만 attach/detach |
| `android/slave/.../vision/CameraPreview.kt` | UI 바인딩 제거, CameraHost attach/detach로 전환 |
| `android/slave/.../service/FarmerService.kt` | FGS 타입 `dataSync\|camera` (Android 11+, 실패 시 fallback). CameraHost.start/stop 제어 |
| `android/slave/.../service/FarmerEngine.kt` | `capture_photo` 명령 처리 (SSE + 폴링 fallback). 촬영→분석→업로드 즉시 실행. `awaitCameraReady()` 대기 로직. SSE 미연결 시 pollCommands 재활성화 |
| `android/slave/.../MainActivity.kt` | onStart에서 서비스 재지시 — 부팅 직후 백그라운드 시작으로 카메라가 비활성이었던 경우 복구 |
| `android/slave/.../network/ApiService.kt` | uploadPhoto에 commandId 파라미터 추가 |
| `android/slave/AndroidManifest.xml` | `FOREGROUND_SERVICE_CAMERA` 권한, `foregroundServiceType="dataSync\|camera"` |
| `backend/src/routes/photos.js` | 사진 업로드 시 마스터에게 `photo_ready` SSE 이벤트 전송 (commandId 포함) |
| `android/master/.../ui/LiveCameraScreen.kt` | 사진 촬영 요청 버튼 + `photo_ready` SSE 수신 즉시 표시 (Coil AsyncImage) |
| `android/master/.../data/AppLocale.kt` + strings.xml (ko/en) | 사진 촬영 관련 다국어 문자열 9개 |

**동작 흐름:**
1. 마스터: LiveCamera → "📸 사진 촬영" → `POST /api/commands` (action=capture_photo)
2. 백엔드: SSE로 slave에 즉시 전달
3. Farmer: 앱이 내려가 있어도 CameraHost가 유지한 카메라로 즉시 촬영 → 분석 → 업로드 (commandId 첨부)
4. 백엔드: 업로드 완료 시 `photo_ready` SSE를 마스터에 전송
5. 마스터: 사진 즉시 표시 (40초 타임아웃)

**검증:** `:slave:app:compileDebugKotlin` ✅ / `:master:app:compileDebugKotlin` ✅

## 3. APK 출력 경로 수정 (commit `f10996e`)

- `android/build.gradle.kts`: APK 복사 경로를 `android/../..` (= `C:\dist`) → 레포 내 `OOJOO-FARM/dist/`로 수정
- 빌드: `:slave:app:assembleDebug :master:app:assembleDebug` ✅
- 결과물: `dist/oojoo-farm-farmer-debug.apk` (17.3MB), `dist/oojoo-farm-master-debug.apk` (24.9MB)

## 4. GCP 배포 갱신

- VM에서 `git pull` (commit `0a0d51c` 반영) → `oojoo.service` 재시작 → active
- 헬스체크 `200 OK`

## 남은 작업

- 실기기 APK 설치 후 E2E 테스트 (마스터 사진 요청 → 백그라운드 Farmer 촬영 → 수신 확인)
- Android 14+ 실기기에서 while-in-use 제한 동작 확인
- release 빌드 (서명) — 현재 debug만
