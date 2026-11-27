---
name: oojoo-project-map
description: OOJOO-FARM 프로젝트 구조·핵심 파일·아키텍처 요약. 코드 탐색/수정 전 컨텍스트 파악용.
---

# OOJOO-FARM 프로젝트 맵

## 구성

| 경로 | 내용 |
|---|---|
| `android/master` | 사용자용 앱 (Compose, 패키지 `com.oojoo.farm.master`) |
| `android/slave` | Farmer 앱 — 자율 재배 기기 (패키지 `com.oojoo.farm.slave`) |
| `backend` | Node.js/Express + SQLite(`node:sqlite`), 포트 4000 |
| `ml/plant_health` | 식물 건강 CNN 학습 코드 + metrics |
| `dist` | 빌드된 APK 출력 |
| `working_diary` | 작업 일지 (규칙: README.md) |
| `docs` | PRD, 빌드 노트 |

## 핵심 파일 (slave)

- `service/FarmerService.kt` — Foreground Service (dataSync+camera), WakeLock
- `service/FarmerEngine.kt` — 자율 루프 싱글턴: 하트비트(30s), SSE 명령 수신, ROI 모니터링(20s), 관수/퇴치/촬영
- `vision/CameraHost.kt` — 서비스 소유 카메라 라이프사이클 (앱 내려가도 유지)
- `vision/CameraHolder.kt` — use case 공유 홀더 (captureStill/capture3s/captureFrame)
- `vision/PlantAnalyzer.kt` — 휴리스틱 분석 + `analyzeRoi` (ROI crop 진단)
- `vision/PlantHealthNet.kt` — 종별 tiny CNN (`assets/models/{species}.bin`, OJP1 포맷)
- `vision/RoiStore.kt` — 식물별 ROI 저장 (`files/rois.json`, 정규화 좌표)
- `ui/RoiEditorScreen.kt` — ROI 드래그 지정 화면
- `album/PlantAlbum.kt` — 성장 사진 앨범 (숏클립 소스)

## 핵심 파일 (master)

- `ui/LiveCameraScreen.kt` — capture_photo/capture_video 요청 + SSE로 응답 수신
- `network/ServerEndpoint.kt` — 서버 URL 검증

## 백엔드 라우트 (app.js)

- `/api/commands` — 명령 등록 + SSE (`/sse/slave/:id`, `/sse/master/:id`)
- `/api/photos` — 업로드 시 `photo_ready` SSE 이벤트
- `/api/videos`, `/api/pairing`, `/api/plants`, `/api/policy`, `/api/weather` 등

## 명령 흐름

master → `POST /api/commands` → SSE → slave `handleSSECommand`
- `capture_photo` / `capture_video` / `generate_growth_clip` / `water` / `fan` / `laser` / `pause` / `resume`
- slave 폴링 fallback: `GET /api/commands/pending/:slaveId` (SSE 끊겼을 때)

## 모델 종 매핑 (PlantHealthNet ALIAS)

basil, cherry_tomato, cactus, herb, strawberry, pepper, pumpkin, zucchini
매핑 없으면 색 통계 휴리스틱 (modelId="heuristic")

## 통신 규칙

- 앱은 `usesCleartextTraffic="true"` — http 서버 주소 그대로 사용
- slave 인증: `x-session-key` 헤더
- 오프라인 시 이벤트 SharedPreferences 큐 적재 → 재연결 시 flush
