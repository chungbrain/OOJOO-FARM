# 2026-11-26 온디바이스 상태검출 모듈 — master 식물 카드에서 추가/삭제, farmer 모델 다운로드·판독·보고 (on-device-detector-modules)

## 요구사항

1. farmer 에는 상태검출별 온디바이스 AI 모델이 있다 (예: 식물별 건강상태 판별, 해충 검출, 관수 주기 모니터링).
2. 이 상태검출 모듈을 master 의 식물 카드에서 추가/삭제할 수 있어야 한다.
3. 모듈을 추가하면 farmer 가 서버에서 관련 모델을 내려받아 영상을 판독하고 결과를 master 에 돌려준다.
4. farmer 는 사진의 식물별 RoI 를 보고 주기적으로, 그리고 이벤트가 있을 때 판독한다.

## 기존 상태 (변경 전)

- farmer 는 건강등급(health_grade.bin)·해충(pest.bin)·종별 진단(<species>.bin)을 앱 assets 에 내장, 모든 RoI 에 대해 항상 실행.
- 서버에서 모델을 받는 경로가 없었고, master 가 기능을 켜고 끌 방법도 없었다.
- 식물 목록 재동기화는 앱 시작 때만 (주석은 "5분마다"였지만 호출 없음) → master 의 RoI 변경이 재시작 전까지 반영 안 됨.

## 설계

| 구성 | 내용 |
|---|---|
| 모듈 카탈로그 | 서버 `backend/src/lib/detectors.js` 가 단일 원천 — `health_grade`(5등급) / `pest`(4단계) / `watering`(종별 모델의 water_low·water_high → dry/ok/wet) |
| 모델 배포 | `backend/models/*.bin` (assets 와 같은 OJP1), `GET /api/detectors/models/:file`, ETag = sha256. 재학습 후 `scripts/sync-detector-models.mjs` |
| 식물별 켜짐 | `plants.detectors_json` (NULL = 기본 3종 → 기존 동작 유지). 추가/삭제 시 farmer 에 `detectors_sync` 명령 (대기 중이면 재사용) |
| farmer 다운로드 | `ModelStore` — 크기·sha256 검증 후 `{sha}.bin` 보관, 디스크 파일도 읽을 때 재검증, 꺼진 모듈 모델은 삭제 |
| 판독 계기 | 주기(roi_interval) + 이벤트(모듈 추가 / master 지금 판독 / 관수 직후·30분 뒤 / Fan·Laser 1분 뒤 / 건강 악화 확정 시 해충·관수) |
| 결과 반환 | `POST /api/detectors/results` — 라벨 변화·이벤트면 즉시, 같으면 5분에 1회. master SSE `detector_result` push |
| 준비 상태 | `POST /api/detectors/state` — downloading / ready / error / unsupported(종 모델 없음 → 색 추정) / no_roi |
| 관수 주기 | 관수 → dry 연속 2회 확정까지의 시간, 최근 5회 평균 → 다음 관수 예상. 관수 후 1시간 안의 dry 는 제외 |
| 호환 | 서버가 `/api/detectors` 를 모르면(구버전) 내장 모델로 3종 모두 켠 기존 동작 + 보고 생략. 오프라인이면 마지막 카탈로그·식물 목록·받아 둔 모델 사용 |

모듈이 꺼지면 관련 알림도 멈춘다: health_change ← 건강상태, water_low·RoI 자동관수 ← 관수, pest_detected ← 해충.
전체 화면 판정의 자동관수/해충 대응도 어느 식물에든 해당 모듈이 켜져 있을 때만 (모든 식물에서 빼면 멈춤).

## 변경 내역

| 파일 | 내용 |
|---|---|
| `backend/src/lib/detectors.js` (신규) | 카탈로그, 모델 파일 해시(mtime 캐시), 기본 세트, `plantDetectors` |
| `backend/src/routes/detectors.js` (신규) | 카탈로그·모델·식물별 조회/추가/삭제·이력·결과·상태 API, `queueDetectorSync` |
| `backend/src/db.js` | `plants.detectors_json`, `detector_results`, `detector_states` |
| `backend/src/routes/plants.js` | 식물 응답에 `detectors`, RoI·farmer·종 변경/식물 등록 시 `detectors_sync`, 삭제 정리 |
| `backend/src/routes/pairing.js`, `lib/validate.js`, `app.js` | 연결 해제 정리, `detectors_sync`/`run_detectors` 명령, 라우트 등록 |
| `backend/models/` (신규) | OJP1 모델 16개 (assets 복사) |
| `scripts/sync-detector-models.mjs` (신규) | assets → backend/models 동기화 (`--check`) |
| `android/slave/.../detect/DetectorLogic.kt` (신규) | 순수 로직: softmax, 관수 라벨, `DetectorPlanner`, `ReportGate`, `WateringCycleTracker` |
| `android/slave/.../detect/ModelStore.kt` (신규) | 모델 다운로드·검증·보관·정리 |
| `android/slave/.../detect/DetectorManager.kt` (신규) | 카탈로그/식물 동기화, 모델 준비, 판독, 결과·상태 보고 |
| `android/slave/.../service/FarmerEngine.kt` | RoI 루프를 주기+이벤트로, 모듈 결과로 알림·자동관수, 명령 2종, 5분 식물 재동기화, 이벤트 훅 |
| `android/slave/.../vision/PlantAnalyzer.kt` | `cropRoi` / `analyzeCrop` 로 분리 (등급·해충은 모듈이 판독) |
| `android/slave/.../ui/DashboardScreen.kt` | RoI 현황에 모듈 판독 요약 한 줄 |
| `android/master/.../ui/DetectorModules.kt` (신규) | 판독 결과 카드, 추가/삭제 다이얼로그 |
| `android/master/.../ui/PlantListScreen.kt` | 식물 카드 `🧠 AI n` 버튼 → 다이얼로그 |
| `android/master/.../ui/PlantDetailScreen.kt` | AI 검출 모듈 카드, 추가/삭제, 지금 판독(결과 올 때까지 3초 간격 확인) |
| `values/strings.xml`, `values-en/strings.xml` | 신규 48개 |
| `docs/farmer-features.md` | §8 F6 검출 모듈 |

## 검증 결과

- 백엔드 `node --test test/detectors.test.js` ✅ 11/11 (카탈로그·다운로드·경로 차단·추가/삭제·명령 중복 방지·결과 검증/권한·삭제 경합·상태·관수 간격·RoI 변경 재동기화·서버/앱 모델 일치)
- 백엔드 전체 `npm test` 125/126 — 실패 1건(app.test.js SIGTERM graceful shutdown)은 변경 전에도 같은 Windows 환경 이슈
- farmer `:slave:app:testDebugUnitTest` ✅ 29/29 (DetectorLogic 15, ModelStore 6, 계약 3, 기존 5)
- master `:master:app:testDebugUnitTest` ✅ 19/19 (DetectorModules 4, 기존 15)
- 계약 테스트 fixture 는 실제 백엔드로 만든 응답 (`src/test/resources/*_sample.json`)
- `node scripts/verify-locales.mjs` ✅, `node scripts/sync-detector-models.mjs --check` ✅
- `./scripts/build-dist.sh` ✅ → master 19,945,320 bytes / farmer 18,488,491 bytes
- **실기기·에뮬레이터 확인 미실시** — 이 PC 에 에뮬레이터 없음. 모델 다운로드→판독→보고의 실제 동작(카메라 프레임, 네트워크)은 확인하지 못함. 순수 로직·서버 계약·컴파일까지만 검증

## 배포 주의

- 백엔드 변경은 GCP(34.50.40.104)에 수동 재배포 필요 (`git pull` + `oojoo.service` 재시작). 재배포 전에는 앱이 구버전 서버로 판단해 내장 모델로 기존 동작을 유지하고, master 의 AI 카드/버튼은 숨겨진다.
- **farmer APK 서명 키 변경**: dist 의 이전 farmer APK 는 a87301b0, 이번 것은 이 PC 키 d0a5726c → 기존 farmer 앱을 삭제 후 설치해야 하고, 삭제하면 페어링을 다시 해야 한다. master 는 2026112601 부터 이미 d0a5726c.

## 커밋

- `ef826db feat(backend): on-device detector modules — catalog, model download, per-plant add/remove, results`
- `d76ce56 feat(farmer,master): detector modules added/removed from the plant card, farmer downloads models and reports readings`
- `0e663ce build: debug APKs 2026112603 with on-device detector modules`

## 남은 작업

- 실기기에서: 모듈 추가 → 모델 다운로드 → 첫 판독 도착, 삭제 → 모델 파일 정리, 지금 판독, 관수 후 주기 기록
- GCP 백엔드 재배포
- 공용 debug keystore 고정 (PC 마다 서명이 달라 재설치가 반복되는 문제)
- 관수 주기 모듈은 종별 진단 모델의 water 클래스를 해석한다 — 전용 수분 스트레스 모델은 아직 없음
