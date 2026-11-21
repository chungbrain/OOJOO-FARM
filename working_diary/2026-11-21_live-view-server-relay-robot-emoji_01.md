# 작업 일지 — 2026-11-21 (2차 세션)

## 실시간 보기 서버 경유 3초 라이브 + 농장 씬 로봇 이모지 교체

**요구사항:**

1. master "카메라 보기"의 "실시간 보기"가 farmer로부터 라이브스트림 영상을 받도록 개발 — 화면을 3초마다 refresh해서 라이브로 변환
2. "나의 농장" 씬의 로봇 그림 교체 — 기존 primitives 조립 로봇이 별로라, 하단 메뉴바 farmer(🤖) 모양으로 통일

**커밋 (push `c5dd19f..e558176`):**

| 커밋 | 내용 |
|---|---|
| `d87a9a2` | 서버 경유 실시간 보기 — farmer 3초 프레임 push / master 3초 폴링 화면 갱신, live_start/live_stop 명령 |
| `e558176` | 농장 씬 로봇을 farmer 이모지(🤖) 피겨로 교체 |

---

## ① 실시간 보기 — 서버 경유 3초 라이브

**배경/원인:**

- 기존 "실시간 보기"는 farmer의 MJPEG 스트림(`stream_url`)에 **LAN 직결**(WebView `<img>`) — 마스터와 farmer가 같은 네트워크일 때만 동작하고, 서버를 경유하지 않아 외부망에서 불가
- 요구는 "farmer로부터 라이브스트림을 받되, 화면을 3초마다 refresh해 라이브처럼 보이게" 하는 것 → farmer가 주기적으로 프레임을 백엔드에 push하고 master가 같은 주기로 pull하는 **서버 경유 릴레이**로 설계

**아키텍처 (동작 흐름):**

```
master                          backend                      farmer(slave)
  │ POST /api/commands            │                             │
  │ action=live_start ────────────▶ SSE/poll 전달 ──────────────▶ 라이브 릴레이 루프 시작
  │                               │                             │ 3초마다 최신 프레임
  │                               │◀── POST /api/live/:id/frame ─ push (세션키 인증)
  │                               │ (slaveId별 최신 1장만 메모리 유지, 12초 TTL)
  │ 3초마다 GET /api/live/:id/frame                              │
  │ ◀──────────── image/jpeg ─────│                             │
  │ 화면 refresh → 라이브화         │                             │
  │ [종료] live_stop ─────────────▶ ───────────────────────────▶ 루프 중단
```

- farmer는 `live_start` 수신 시 3초마다 프레임 push — 소스는 `CameraHolder.latestFrame`(ImageAnalysis가 항상 갱신하는 640w q70 소형 JPEG, MjpegStreamer와 공유), 오래됐으면 `captureFrame` 즉시 캡처 fallback. 앱이 백그라운드여도 서비스가 카메라를 유지하므로 동작
- master는 첫 프레임 수신 후 **3초 폴링으로 화면을 갱신** — LIVE 배지(신호 끊기면 회색), "3초마다 자동 갱신" 안내, 신호 끊김/미응답 안내 (첫 프레임 15초 미수신, 스트리밍 중 3회 연속 미수신)
- 종료 버튼/화면 이탈 시 `live_stop` 전송으로 farmer의 push 루프 정리 (onCleared는 별도 IO 스코프로 best-effort)

**변경 파일:**

| 파일 | 변경 |
|---|---|
| `backend/src/routes/live.js` | 신설 — frame push/pull/status 릴레이 (multer 오류 400 처리 포함) |
| `backend/src/app.js` | `/api/live` 마운트 |
| `backend/src/lib/validate.js` | COMMAND_ACTIONS에 `live_start`, `live_stop` 추가 |
| `backend/test/live_stream.test.js` | 신설 — 명령 등록, push→pull 최신 1장, 세션키 인증, 잘못된 필드 400 (4개) |
| `android/slave .../ApiService.kt` | `uploadLiveFrame` (multipart POST) |
| `android/slave .../FarmerEngine.kt` | 라이브 릴레이 루프 + SSE/poll 양쪽 명령 처리 + stop() 정리 |
| `android/master .../LiveCameraScreen.kt` | 기존 LAN MJPEG WebView 제거 → live_start 전송 + 3초 폴링 화면 갱신 + LIVE 배지 |
| `android/master .../AppLocale.kt`, `strings.xml`(ko/en) | live_fetching 문구 갱신, live_no_signal/live_badge/live_refresh_note 신설 |

## ② 농장 씬 로봇 — farmer 이모지 교체 (`FilamentViewer.kt`)

- 기존: 스틸 헤드+그린 몸통+바퀴를 primitives로 조립한 커스텀 로봇 (안테나/깜빡이는 눈/화면/바퀴 6부품)
- 변경: 하단 메뉴바 farmer 탭과 같은 **🤖 이모지 피겨**(38sp)로 교체
- 제거: `RobotEye`/`RobotWheel` 컴포저블, Robot* 색상 5종, 눈 깜빡임(keyframes) 애니메이션
- 유지: 순찰 이동 방향 반전(facing)·기울기(tilt), 도착 시 커짐(scale), 관수 물방울(dropFall)

## 검증 결과

- master/slave `:app:compileDebugKotlin` 통과 (android 루트 gradle 2모듈 동시)
- 백엔드 `npm test` — **90 pass / 1 fail**: 실패한 `SIGTERM graceful shutdown`은 변경 전(stash 후 재현)에도 동일 실패하는 **Windows 플랫폼 종속 이슈** (Windows는 SIGTERM 리스너 동작 없이 강제 종료 → exit code null). Linux/GCP 배포 환경에서는 무관

## 배포·빌드 (세션 후반)

- **GCP 백엔드 배포**: VM `sudo git pull` → `1b84ca9..656d5ad` 반영, `systemctl restart oojoo` → **active**, `/health` 200, `/api/live/:id/status` 정상 응답, `POST /api/commands {action:"live_start"}` → commandId 발급(queued) 확인
- **APK 재빌드**: 버전 라벨 `2026112015` → **`2026112102`**로 상향 후 `assembleDebug` BUILD SUCCESSFUL — `dist/oojoo-farm-farmer-debug.apk` (18.4MB) / `dist/oojoo-farm-master-debug.apk` (18.9MB), 17:41 갱신 (자동 복사 훅 미동작으로 수동 복사)
- JAVA_HOME: Android Studio jbr 경로가 현재 PC에 없음 → `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot` 사용

## 남은 작업

- 기기에 새 APK(2026112102) 설치 — farmer/master 둘 다 업데이트해야 라이브 보기 동작 (구버전 APK는 live_start 미지원)
- 라이브 화면 해상도/품질 상향(현재 스트림 프레임 재사용 640w q70) — 필요 시 캡처 전용 고해상도 경로 추가 가능
