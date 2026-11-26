# Farmer 기능 정의 — Jev 건강판단 / 해충탐지 / 변화 알림 / 자동 방향 촬영

> 작성: 2026-10-21. PC 시뮬레이션(`simulation/`) 선행 검증 후 Android(`android/slave`)에 동일 스펙으로 이식한다.

## 0. 용어 — "Jev" 등급 모델

본 프로젝트에서 **Jev**는 "초경량·고속·온디바이스 판단 모델"의 등급 명칭으로 정의한다.

- 조건: CPU-only 추론 1회 수 ms 이내, 모델 파일 수십 KB 이하, 외부 런타임(TFLite/NNAPI 등) 없이 순수 코드로 forward 가능
- 구현 계열: 기존 OJP1 tiny CNN(conv8-16-24 + GAP + dense)과 동일 아키텍처. 이미 종별 6클래스 모델이 이 등급으로 동작 중이다
- 이 문서가 정의하는 두 신규 모델(`health_grade`, `pest`) 모두 Jev 등급이다

## 1. F1 — Jev 건강판단 모델 (JevHealthNet)

식물의 **종합 건강등급**을 5단계로 빠르게 판단한다. 기존 종별 6클래스 모델(원인 분류: 물부족/햇빛부족 등)과 달리 **등급(severity)** 을 판단하는 별도의 범용 모델이다.

| 항목 | 값 |
|---|---|
| 모델 id | `health_grade` (파일 `assets/models/health_grade.bin`) |
| 입력 | 48×48 RGB (ROI crop → 다운스케일) |
| 클래스 | `very_healthy` 매우건강 / `healthy` 건강 / `fair` 보통 / `poor` 나쁨 / `very_poor` 매우나쁨 |
| 아키텍처 | conv(3→8)-pool-conv(8→16)-pool-conv(16→24)-pool-GAP(24)-dense(24→5), OJP1 |
| 크기/속도 | ~20KB, 순수 CPU forward 수 ms 이내 (Jev 등급) |
| 범용성 | 종 무관(8종 혼합 학습) — ROI의 `species`와 무관하게 적용 |
| 판단 축 | 엽록소 밀도(잎 커버리지), 황변/갈변 정도, 시듦 정도 |

등급 판단은 ROI 모니터링 사이클마다(기본 20초) 수행한다. 종별 원인 진단(기존 6클래스)은 그대로 병행하며, 등급 모델은 "지금 이 식물의 상태가 좋은가/나쁜가"를 담당한다.

## 2. F2 — 해충발생 전용 모델 (PestNet)

**건강등급 판단과 완전히 별도의 모델**로 해충 발생 정도를 CNN으로 평가한다.

| 항목 | 값 |
|---|---|
| 모델 id | `pest` (파일 `assets/models/pest.bin`) |
| 입력 | 48×48 RGB (동일 ROI crop) |
| 클래스 | `none` 해충없음 / `light` 경미발생 / `moderate` 보통발생 / `severe` 심각발생 |
| 아키텍처 | 동일 OJP1 tiny CNN, dense(24→4) — Jev 등급 |
| 독립성 학습 | 다양한 건강등급 배경 위에 해충 증상(식해 구멍/검은 반점)을 랜덤 조합해, **등급(황변 등)과 해충 증상을 독립적으로 구분**하도록 학습 |

기존 종별 6클래스의 `pest` 클래스는 원인 진단용으로 유지하고, 발생 **정도 평가**는 이 전용 모델이 담당한다. 퇴치 대응(fan/laser)은 PestNet 결과가 `light` 이상일 때 트리거한다.

## 3. F3 — 건강상태 변화 시 Master 알림

ROI 모니터링 결과 건강등급이 **이전 사이클과 다른 등급으로 전이**되면 즉시 master에게 알린다.

**이벤트 스펙** (`POST /api/events`) — payload 는 플랫 키(Android slave `EventRequest`의 `Map<String,String>`과 동일 스키마):

```json
{
  "slaveId": "…", "plantId": "…", "type": "health_change",
  "payload": {
    "from": "healthy", "to": "poor",
    "fromKo": "건강", "toKo": "나쁨",
    "confidence": 0.91, "modelId": "health_grade",
    "greenness": 0.31,
    "pestFrom": "none", "pestTo": "severe", "pestLevelKo": "심각발생",
    "pestConfidence": 0.88, "pestModelId": "pest"
  }
}
```

(`pest*` 필드는 해충 등급 전이가 동반된 경우에만 포함)

- 해충 등급 전이는 `health_change` payload의 `pest*` 플랫 필드(`pestFrom`/`pestTo`/`pestLevelKo`/…)로 함께 전달한다 (별도 이벤트 폭증 방지)
- **전이 확정**: 새 등급/해충 상태가 **연속 2회 관측**될 때만 알림을 발행한다 — 단일 사이클 오분류(플리커)가 마스터 알림을 유발하지 않도록 한다 (PC 시뮬레이션 S3에서 발견해 규격에 반영)
- 백엔드는 noti 대상 이벤트(`health_change`, `water_low`, `pest_detected`, `harvest_ready`)를 events 테이블 저장과 함께 **master SSE**(`GET /api/commands/sse/master/:slaveId`)로 실시간 push한다. 알림 센터(`GET /api/notifications/:userId`) 폴링은 기존대로 동작
- 동일 등급 유지 시 알림 없음 (확정된 전이 시에만 1회)

## 4. F4 — 카메라 위치 기반 자동 가로/세로 촬영

master의 `capture_photo` 명령 수신 시, farmer의 **카메라 물리 설치 위치**에 따라 가로/세로 모드를 자동 선택한다.

**정책** (`policies.camera_mount`, 원격 설정):

| 값 | 의미 |
|---|---|
| `auto` (기본) | 디스플레이 회전 감지 기반 (기존 targetRotation 로직과 동일) |
| `landscape` | 카메라를 가로로 설치한 고정 기기 — 가로모드 촬영 고정 |
| `portrait` | 카메라를 세로로 설치한 고정 기기 — 세로모드 촬영 고정 |

고정 설치(헤드리스) 기기는 회전 감지가 신뢰 불가능한 경우가 많아, 명시 지정이 정확한 방향을 보장한다.

**방향 리졸버** (`resolveCaptureOrientation`, Android와 PC 시뮬레이션 공용 규격):

```
입력: sensorOrientation(센서 고정 각, 통상 후면카메라 90°)
      displayRotation(0..3, auto 판단용)
      mount(auto | landscape | portrait)

targetRotation = mount=='landscape' ? (naturalPortrait ? 1 : 0)
               : mount=='portrait'  ? (naturalPortrait ? 0 : 1)
               : displayRotation
exifRotation   = (sensorOrientation - 90×targetRotation + 360) % 360
captureMode    = 센서 natural 방향과 targetRotation의 XOR → upright 이미지 aspect가
                 가로면 'landscape', 세로면 'portrait'
```

- 자연 세로 센서(90°) + `landscape` → targetRotation 1 → EXIF 0° → **640×480 가로 사진**
- 자연 세로 센서(90°) + `portrait` → targetRotation 0 → EXIF 90° → **480×640 세로 사진**
- `auto` + 디스플레이 90°(기기 가로 설치) → 가로, 디스플레이 0°(세로 설치) → 세로

## 5. F5 — PC 시뮬레이션 (선행 검증)

Android 이식 전, 전 기능을 PC(Node.js)에서 **실제 백엔드(:4000)에 연결**해 E2E 시뮬레이션한다.

```
simulation/
├─ src/
│  ├─ models/ojp1.js        # OJP1 파서+forward (PlantHealthNet.kt와 동일 연산)
│  ├─ models/jevHealth.js   # JevHealthNet — 5단계 등급 라벨/추론
│  ├─ models/pestNet.js     # PestNet — 해충 4단계 라벨/추론
│  ├─ vision/plantScene.js  # 합성 식물 장면 (등급×해충 파라미터)
│  ├─ vision/png.js         # 경량 PNG 인코더 (의존성 0)
│  ├─ engine/orientation.js # 방향 리졸버 (F4 규격 구현)
│  ├─ engine/cameraSim.js  # 가상 카메라 — 센서 방향/설치 위치 기반 촬영
│  ├─ engine/farmerSim.js  # 자율 루프 — heartbeat/ROI 모니터링/전이 감지/명령 처리
│  ├─ net/api.js           # REST+SSE 클라이언트 (x-session-key 인증 미러)
│  ├─ master/masterSim.js  # 마스터 시뮬레이터 — 페어링/식물등록/명령/알림 수신
│  └─ index.js             # 시나리오 러너
└─ test/                    # 모델 round-trip(Python 검증벡터)·방향·전이 감지 단위 테스트
```

**시나리오**

| ID | 검증 내용 | 백엔드 |
|---|---|---|
| S1 | 5등급 이미지 → JevHealthNet 판정 일치율 | ✗ |
| S2 | 해충 4단계 판정 + "건강 나쁨·해충 없음" 독립성 | ✗ |
| S3 | 건강 악화(매우건강→…→매우나쁨) → `health_change` → master SSE/알림 센터 수신 | ✓ |
| S4 | `camera_mount` = auto/landscape/portrait → capture_photo → 사진 가로/세로 확인 | ✓ |

## 6. Android 이식 매핑 (PC 검증 후 진행)

| 기능 | Android 파일 | 변경 |
|---|---|---|
| JevHealthNet | `vision/HealthGradeNet.kt` (신규) | OJP1 로드 재사용, dense 24→5 |
| PestNet | `vision/PestNet.kt` (신규) | OJP1 로드 재사용, dense 24→4 |
| 전이 감지+알림 | `service/FarmerEngine.kt` `runRoiMonitoring()` | 등급 추적 상태 추가 → `health_change` 이벤트 |
| 자동 방향 촬영 | `vision/CameraHost.kt` + `vision/OrientationPolicy.kt` (신규) | `camera_mount` 정책 → `applyTargetRotation` 적용 |
| 정책 동기화 | `model/Models.kt` PolicyResponse + `FarmerEngine.syncPolicy()` | `camera_mount` 필드 |
| 알림 표시 | master `ui/NotificationScreen.kt`, `PlantDetailScreen.kt` | `health_change` 라벨/색상 추가 |

## 7. 데이터 플로우 (전체)

```
[Farmer] ROI 20s 사이클
  ├─ JevHealthNet.infer(crop)   → 등급(5단계)
  ├─ PestNet.infer(crop)        → 해충(4단계, 별도 모델)
  ├─ POST /api/analysis/report  → plant_analysis (등급/해충 포함)
  └─ 등급 전이 감지              → POST /api/events {type:'health_change'}
                                      → 백엔드: events 저장 + master SSE push
                                        → master: 실시간 수신 + 알림 센터 표시

[Master] capture_photo 명령
  → farmer: camera_mount 정책 기반 가로/세로 자동 촬영
  → POST /api/photos/upload → photo_ready SSE → master 표시
```

## 8. F6 — 온디바이스 상태검출 모듈 (master 에서 식물별 추가/삭제)

> 작성: 2026-11-26. 건강등급(F1)·해충(F2)·관수 감시를 "모듈"로 묶어 master 가 식물마다 켜고 끈다.
> 모듈을 켜면 farmer 가 서버에서 모델을 내려받아(sha256 검증) 그 식물의 RoI 를 판독하고 결과를 돌려준다.

### 8.1 모듈 카탈로그 (서버가 단일 원천 — `backend/src/lib/detectors.js`)

> 2026-11-26 이후: 어떤 모델 파일·라벨을 내려줄지는 model-manager 레지스트리의 배포 버전이 정한다 — 새 버전 배포, 라벨 추가, 새 종 모델(별칭 `speciesAliases`), 새 검출 모듈이 모두 이 카탈로그로 Farmer 에 전달된다. 자세한 내용: [docs/model-manager.md](model-manager.md)

| id | 이름 | 모델 | 출력 라벨 |
|---|---|---|---|
| `health_grade` | 🌿 건강상태 판별 | `health_grade.bin` | very_healthy / healthy / fair / poor / very_poor |
| `pest` | 🐛 해충 검출 | `pest.bin` | none / light / moderate / severe |
| `watering` | 💧 관수 주기 모니터링 | 식물 종별 모델 `<species>.bin` (6클래스 중 water_low/water_high 해석) | dry / ok / wet |

- 모델 파일은 `backend/models/*.bin` (앱 assets 와 같은 OJP1). 재학습 후 `node scripts/sync-detector-models.mjs` 로 맞춘다 — 불일치는 백엔드 테스트가 잡는다.
- 분류형 모듈은 카탈로그의 `labels` 순서가 모델 출력 순서다 → 서버에 새 분류 모듈을 추가해도 farmer 앱 수정 없이 동작.
- 기본 세트: 이 기능 이전 식물/신규 식물은 3종 모두 켜짐 (`plants.detectors_json` NULL = 기본값) — 기존 동작과 같다.

### 8.2 API

| 메서드 | 경로 | 호출자 | 설명 |
|---|---|---|---|
| GET | `/api/detectors` | farmer | 카탈로그 (모델 url·sha256·크기) |
| GET | `/api/detectors/models/:file` | farmer | 모델 파일 (카탈로그 파일만, ETag=sha256) |
| GET | `/api/detectors/plant/:plantId` | master | 모듈별 켜짐·farmer 준비 상태·최신 결과·서버 관수 간격 |
| POST / DELETE | `/api/detectors/plant/:plantId/:moduleId` | master | 추가 / 삭제 → farmer 에 `detectors_sync` 명령 (대기 중이면 재사용) |
| GET | `/api/detectors/plant/:plantId/history` | master | 결과 이력 |
| POST | `/api/detectors/results` | farmer(세션키) | 판독 결과 (자기 식물만, 라벨 검증, 꺼진 모듈 결과는 버림) |
| POST | `/api/detectors/state` | farmer(세션키) | 모델 준비 상태 downloading / ready / error / unsupported / no_roi |

- `GET /api/plants/slave/:slaveId` 의 각 식물에 `detectors: [id…]` 가 붙는다.
- 식물의 RoI·담당 farmer·종이 바뀌어도 서버가 `detectors_sync` 를 보내 farmer 가 즉시 재동기화한다.
- master `run_detectors` 명령 = 지금 판독.

### 8.3 Farmer 동작 (`android/slave/.../detect/`)

1. **동기화** (시작·5분마다·`detectors_sync` 명령): 식물 목록의 `detectors` → 모듈별 모델 준비 (`ModelStore`: `{sha256}.bin` 보관, 크기·해시 불일치면 저장 안 함) → 상태 보고. 꺼진 모듈의 모델 파일은 정리.
2. **판독 계기** (`DetectorPlanner`):
   - 주기: 정책 `roi_interval` 마다 모든 RoI × 켜진 모듈
   - 이벤트: 모듈 추가 직후 / master 지금 판독 / 관수 직후 + 30분 뒤 / Fan·Laser 퇴치 1분 뒤 / 건강등급 악화 확정 시 해충·관수
3. **결과 보고** (`ReportGate`): 이벤트 계기·라벨 변화는 즉시, 같은 라벨은 5분에 1회.
4. **관수 주기** (`WateringCycleTracker`): 관수 시각 → 'dry' 연속 2회 확정 시각의 간격을 식물별로 최근 5회 보관 → 평균 마름 주기·다음 관수 예상. 관수 후 1시간 안의 dry 는 주기로 치지 않는다.
5. 꺼진 모듈은 알림도 없다 — 건강 전이(health_change)는 건강상태 모듈, water_low·RoI 자동관수는 관수 모듈, pest_detected 는 해충 모듈이 켜진 식물만. 전체 화면 판정의 자동관수/해충 대응도 어느 식물에든 해당 모듈이 켜져 있을 때만.
6. **호환**: 서버가 `/api/detectors` 를 모르면(구버전) 앱 내장 모델로 3종 모두 켠 기존 동작을 유지하고 보고는 생략. 오프라인이면 마지막 카탈로그·식물 목록과 받아 둔 모델로 계속 판독.

### 8.4 Master UI

- 식물 카드: `🧠 AI n` 버튼 → 모듈 목록 다이얼로그에서 추가/삭제 (삭제는 확인).
- 식물 상세: "AI 검출 모듈" 카드 — 모듈별 최신 판독(라벨·신뢰도·시각·계기), 준비 상태(모델 받는 중 / RoI 필요 / 종 모델 없음 등), 관수 주기, `지금 판독`.
