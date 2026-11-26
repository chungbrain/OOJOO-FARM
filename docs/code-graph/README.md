# OOJOO FARM 코드 그래프

> 2026-11-26 기준. 코드 수준에서 "무엇이 무엇을 부르고, 데이터가 어디로 흐르는지"를 그래프로 정리했다.
> 다이어그램은 Mermaid — GitHub 에서 바로 그려진다.
>
> - **이 문서**: 사람이 정리한 구조·흐름·상태·데이터 모델 (파일·함수 이름은 실제 코드 기준)
> - **[generated.md](generated.md)**: `node scripts/code-graph.mjs` 가 소스에서 만든 모듈 의존 그래프와 API 지도 (코드를 바꾸면 다시 생성)

## 목차

1. [시스템 한눈에](#1-시스템-한눈에)
2. [구성 요소 지도](#2-구성-요소-지도)
3. [핵심 흐름](#3-핵심-흐름) — 페어링·명령 / 검출 모듈 추가 / RoI 판독 회차 / 관수 이벤트 / model-manager 학습·배포 / GitHub / 식물 상세 화면
4. [상태 기계](#4-상태-기계)
5. [데이터 모델](#5-데이터-모델)
6. [기능 → 파일 찾기](#6-기능--파일-찾기)

---

## 1. 시스템 한눈에

```mermaid
flowchart LR
  subgraph phone["내 폰"]
    M["Master 앱<br/>android/master"]
  end
  subgraph farm["식물 옆"]
    F["Farmer 앱<br/>android/slave"]
    HW["ESP32<br/>밸브 · Fan · 레이저"]
  end
  subgraph cloud["GCP VM 34.50.40.104:4000"]
    B["백엔드<br/>backend/src (Express + SQLite)"]
    DB[("oojoo.db")]
    FS[("data/<br/>photos · videos · ml")]
  end
  subgraph pc["운영자 PC"]
    MM["로컬 model-manager<br/>ml/model-manager :4900"]
    REG[("ml/registry/")]
  end
  GH[("GitHub<br/>chungbrain/OOJOO-FARM")]

  M -- "REST + SSE(실시간 알림)" --> B
  F -- "REST + SSE(명령)<br/>x-session-key" --> B
  F -- "BLE (Nordic UART)" --> HW
  M -. "LAN MJPEG :8613" .-> F
  B --- DB
  B --- FS
  MM -- "릴리스 업로드·배포<br/>x-admin-token" --> B
  MM --- REG
  MM -- "git commit + push<br/>ml/registry/모델" --> GH
```

| 구성 요소 | 진입점 | 역할 |
|---|---|---|
| Master 앱 | `MainActivity.kt` (Navigation Compose 라우트) | 사용자 폰 — 식물·Farmer 관리, 원격 명령, 알림, 커뮤니티·마켓 |
| Farmer 앱 | `service/FarmerService.kt` → `service/FarmerEngine.kt` | 식물 옆 고정 폰 — 카메라 판독, 자율 관수·해충 대응, 하드웨어 제어 |
| 백엔드 | `server.js` → `app.js` | 계정·페어링·식물·이벤트·사진·명령 큐·검출 모듈 카탈로그·모델 레지스트리 |
| model-manager | `ml/model-manager/server.mjs` | 운영자 PC — 라벨·학습 사진·학습·평가, 서버/GitHub 업로드 |

---

## 2. 구성 요소 지도

### 2.1 Farmer 앱 — `FarmerEngine` 중심

`FarmerEngine` (object, 싱글턴)이 모든 루프를 돌린다. UI(`DashboardScreen`)와 백그라운드(`FarmerService`)가 같은 인스턴스를 공유한다.

```mermaid
flowchart TB
  SVC["FarmerService<br/>(포그라운드 서비스, BootReceiver)"] --> ENG

  subgraph ENG["FarmerEngine"]
    direction TB
    L1["메인 루프 30초<br/>heartbeat · flushQueue · pollCommands(SSE 끊겼을 때)<br/>tick() 전체 화면 자동관수/수확/해충<br/>10틱마다 syncPolicy() + refreshPlants()"]
    L2["날씨 루프 30분<br/>refreshFarmLocation · fetchWeather"]
    L3["roiMonitorLoop()<br/>주기(roi_interval) + 이벤트 대기<br/>→ runRoiMonitoring(periodic)"]
    L4["startSSE()<br/>/api/commands/sse/slave → handleSSECommand"]
    L5["startLiveRelay()<br/>3초마다 프레임 업로드 (live_start~stop)"]
  end

  ENG --> DM["detect/DetectorManager<br/>sync · run · report"]
  DM --> MS["detect/ModelStore<br/>다운로드 + SHA-256 검증"]
  DM --> LOGIC["detect/DetectorLogic<br/>DetectorPlanner · ReportGate · WateringCycleTracker"]
  DM --> OJP["vision/Ojp1<br/>parse · forward"]
  L3 --> PA["vision/PlantAnalyzer<br/>cropRoi · analyzeCrop"]
  PA --> PHN["vision/PlantHealthNet<br/>종별 6클래스"]
  L3 --> CH["vision/CameraHolder<br/>captureFrame"]
  ENG --> HWC["hardware/Hardware.controller<br/>BleHardwareController | Mock"]
  ENG --> API["network/ApiClient.api<br/>(Retrofit ApiService)"]
  ENG --> ALB["album/PlantAlbum<br/>vision/GrowthClipRenderer"]
  ENG --> MJ["service/MjpegStreamer :8613"]
  ENG --> RS["vision/RoiStore<br/>files/rois.json"]
```

### 2.2 Master 앱 — 화면 · ViewModel · API

화면마다 ViewModel 이 `ApiClient.api`(Retrofit)를 부른다. 공용 상태는 `data/`(Session, Prefs, AppLocale), 모델은 `model/Models.kt` 한 파일.

```mermaid
flowchart LR
  NAV["MainActivity<br/>NavHost 라우트"] --> HOME["HomeScreen<br/>농장 화면 · 날씨"]
  NAV --> PL["PlantListScreen<br/>PlantListViewModel"]
  NAV --> PD["PlantDetailScreen<br/>PlantDetailViewModel"]
  NAV --> FL["FarmerListScreen"]
  NAV --> ROI["RoiEditorScreen"]
  NAV --> LIVE["LiveCameraScreen"]
  NAV --> ETC["Community · Market · Family ·<br/>Notification · Messages · Profile ..."]

  PL --> DMOD["DetectorModules.kt<br/>DetectorManageDialog"]
  PD --> DMOD2["DetectorModules.kt<br/>DetectorModulesCard"]
  PD --> GA["GrowthAlbum.kt<br/>GrowthAlbumCard"]
  PD --> ES["EventStats.kt<br/>EventStatsCard"]
  PD --> FG["FarmGrid.kt"]

  PL & PD & HOME & FL & ROI & LIVE & ETC --> APIM["network/ApiClient.api"]
  APIM --> BK[("백엔드 /api/*")]
```

### 2.3 백엔드 — 라우터와 공용 모듈

`app.js` 가 라우터 26개를 `/api/<이름>` 에 붙인다. 검출 모듈·모델 레지스트리는 `lib/detectors.js` + `ml/` 가 맡는다. (전체 import 그래프: [generated.md §1](generated.md#1-백엔드))

```mermaid
flowchart LR
  APP["app.js"] --> R1["routes/plants.js"]
  APP --> R2["routes/detectors.js"]
  APP --> R3["routes/models.js"]
  APP --> R4["routes/commands.js<br/>SSE 허브"]
  APP --> R5["routes/events.js"]
  APP --> RX["users · pairing · watering · photos · videos · live ·<br/>analysis · diagnosis · policy · weather · community · market ..."]

  R1 --> LD["lib/detectors.js<br/>catalog() · plantDetectors()"]
  R2 --> LD
  R2 --> R4
  R1 --> R2
  R3 --> R2
  R3 --> REGB["ml/registry.js<br/>배포 버전 · 별칭"]
  R3 --> ST["ml/stats.js<br/>현장 성능 · 재학습 신호"]
  R3 --> OJ["ml/ojp1.js<br/>업로드 검증"]
  LD --> REGB
  ST --> LD
  REGB --> DEFS["ml/defs.js<br/>내장 모델 정의"]
  R5 --> R4
  R5 --> PUSH["lib/push.js (FCM)"]
```

### 2.4 로컬 model-manager

```mermaid
flowchart LR
  UI["ui/index.html<br/>목록 · 성능 · 학습 데이터 · 학습·배포"] -- "fetch /api/* + x-mm-local" --> SRV["server.mjs<br/>createApp()"]
  SRV --> ST["lib/store.js<br/>ml/registry 파일 읽기·쓰기"]
  SRV --> JB["lib/jobs.js<br/>작업 큐"]
  JB --> WK["lib/worker.js<br/>(worker thread)"]
  WK --> TR["lib/train.js<br/>trainModel · evaluate · augment"]
  WK --> IMG["lib/image.js<br/>JPEG · EXIF · RoI 크롭"]
  TR --> OJ["backend/src/ml/ojp1.js<br/>forward · backward · adaptModel"]
  ST --> DEFS["backend/src/ml/defs.js"]
  SRV --> RM["lib/remote.js<br/>서버 조회 · uploadVersion"]
  SRV --> GT["lib/git.js<br/>status · publish"]
  RM --> BK[("GCP 백엔드 /api/models")]
  GT --> GH[("GitHub")]
```

---

## 3. 핵심 흐름

### 3.1 페어링 → 하트비트 → 명령

```mermaid
sequenceDiagram
  autonumber
  participant M as Master 앱
  participant B as 백엔드
  participant F as Farmer 앱
  M->>B: POST /api/pairing/code (userId)
  B-->>M: 6자리 code (10분 유효)
  F->>B: POST /api/pairing/verify (code)
  B-->>F: slaveId + sessionKey
  Note over F: Prefs 에 저장, 이후 모든 요청에 x-session-key
  loop 30초 (FarmerEngine 메인 루프)
    F->>B: POST /api/pairing/heartbeat (배터리, stream_url, 위치)
  end
  F->>B: GET /api/commands/sse/slave/:slaveId (SSE 연결 유지)
  M->>B: POST /api/commands (action=water 등)
  B->>B: commands 테이블 queued 저장
  B-->>F: SSE data: command
  F->>F: handleSSECommand → attemptWater / capture / ...
  F->>B: POST /api/commands/:id/done
  Note over F,B: SSE 가 끊기면 메인 루프가 GET /api/commands/pending 으로 폴링
```

### 3.2 검출 모듈 추가 → Farmer 가 모델을 받는다

```mermaid
sequenceDiagram
  autonumber
  participant U as 사용자
  participant M as Master<br/>PlantListScreen / PlantDetailScreen
  participant B as 백엔드<br/>routes/detectors.js
  participant F as Farmer<br/>FarmerEngine
  participant D as DetectorManager
  participant S as ModelStore
  U->>M: 식물 카드 🧠 AI n → 해충 검출 [추가]
  M->>B: POST /api/detectors/plant/:plantId/pest
  B->>B: plants.detectors_json 에 추가
  B->>B: queueDetectorSync() — 대기 중 detectors_sync 재사용
  B-->>F: SSE command detectors_sync
  B-->>M: 식물 모듈 목록 (enabled, state, latest)
  F->>F: refreshPlants()
  F->>B: GET /api/plants/slave/:slaveId (plants[].detectors, roi)
  F->>D: sync(slaveId, plants, roiPlantIds)
  D->>B: GET /api/detectors (카탈로그: 모델 url · sha256 · labels · speciesAliases)
  D->>B: POST /api/detectors/state (downloading)
  D->>S: ensure(ModelRef)
  S->>B: GET /api/detectors/models/pest-v2.bin
  S->>S: 크기 · SHA-256 확인 → files/detector_models/(sha).bin
  D->>D: Ojp1.parse → 출력 수 = labels 수 확인 → models[plant,module]
  D->>B: POST /api/detectors/state (ready | no_roi | unsupported | error)
  D->>D: planner.request(module_added, plant, pest)
  Note over D: 다음 RoI 판독 회차가 바로 깨어나 판독 (3.3)
```

삭제도 같은 경로: `DELETE /api/detectors/plant/:plantId/:moduleId` → `detectors_sync` → `sync()` 가 `models`·`latest` 에서 빼고 `ModelStore.retainOnly()` 로 쓰지 않는 모델 파일을 지운다.

### 3.3 RoI 판독 회차 (주기 + 이벤트)

```mermaid
sequenceDiagram
  autonumber
  participant L as roiMonitorLoop
  participant P as DetectorPlanner
  participant C as CameraHolder
  participant A as PlantAnalyzer
  participant D as DetectorManager
  participant E as FarmerEngine<br/>handleRoiResult
  participant B as 백엔드
  L->>P: waitMs(now, lastPeriodicAt, roi_interval)
  Note over L: 다음 주기 / 가장 이른 이벤트 요청까지 대기 — roiWake 채널로 즉시 깨어남
  L->>P: plan(now, 켜진 모듈(RoI 있는 식물), periodic)
  P-->>L: 식물 → (모듈 → 계기)
  L->>C: captureFrame()
  loop RoI 마다
    L->>A: cropRoi(frame, roi) → analyzeCrop (색 통계 + 종별 진단)
    L->>D: run(plantId, 모듈→계기, crop, needWater)
    D->>D: softmax(forward) → 라벨 (관수: waterLabelFromProbs + WateringCycleTracker.observe)
    D-->>L: fresh 판독 결과
    L->>E: handleRoiResult(roi, base, fresh, triggers)
    E->>B: POST /api/analysis/report (5분 스로틀, 이상 시 1분)
    E->>E: trackGradeTransition — 2회 연속이면 health_change
    E->>B: POST /api/events (health_change · water_low · pest_detected)
    E->>E: 관수 모듈이 dry → attemptWater (30분 쿨다운)
    E->>D: report(slaveId, plantId, fresh, triggers)
    D->>B: POST /api/detectors/results (ReportGate: 라벨 변화·이벤트면 즉시, 같으면 5분)
  end
  B-->>B: detector_results 저장 + master SSE detector_result
```

이벤트 계기 (`Triggers`): `module_added` · `run_now`(Master 지금 판독 → `run_detectors` 명령) · `watered` / `watered_followup`(+30분) · `pest_response`(Fan/Laser +1분) · `health_drop`(등급 악화 확정) · `roi_changed`.

### 3.4 관수 → 관수 주기 기록

```mermaid
sequenceDiagram
  autonumber
  participant E as FarmerEngine
  participant H as Hardware.controller
  participant D as DetectorManager
  participant W as WateringCycleTracker
  participant B as 백엔드
  E->>H: openValve(관수량 × 20ms, 최대 60초)
  alt 밸브 열림 (BLE 연결 또는 시뮬레이션)
    E->>B: POST /api/watering/log + POST /api/events (auto_water | manual_water)
    E->>D: onWatered(plantId)
    D->>W: onWatered — 확정 상태 초기화, lastWateredAt 기록
    E->>D: request(watered, 관수+건강) / request(watered_followup, 관수, +30분)
  else 하드웨어 미연결 · 오류
    E->>B: POST /api/events (water_failed)
  end
  Note over W: 이후 판독에서 관수 1시간 뒤의 dry 가 2회 연속이면<br/>주기 = dry 확정 시각 − 관수 시각 (최근 5회 평균 → 다음 관수 예상)
```

### 3.5 model-manager: 사진 → 학습 → 서버 배포

```mermaid
sequenceDiagram
  autonumber
  participant O as 운영자 (브라우저)
  participant S as server.mjs
  participant R as store.js<br/>ml/registry
  participant J as jobs.js + worker.js
  participant B as GCP 백엔드<br/>routes/models.js
  participant F as Farmer들
  O->>S: 사진 + 라벨 업로드 / Farmer 사진 가져오기
  S->>B: GET /api/models/:id/candidates (식물 RoI, 사진 목록)
  S->>B: 원본 사진 다운로드 → image.js cropNormalized(RoI)
  S->>R: addSample — 256px JPEG, sha256 이름, 학습/검증 분할(묶음 단위)
  O->>S: POST /api/models/:id/train (시작 버전, 에폭, 학습률)
  S->>J: enqueue(train)
  J->>J: worker: adaptModel(부모, 라벨) → trainModel(L2-SP) → evaluate(새 모델, 부모)
  J->>R: saveTrainedVersion → versions/vN/model.bin + version.json (metrics, baseline, dataset)
  O->>S: 버전 vN [업로드+배포]
  S->>B: POST /api/models/:id/datasets/missing (sha256 목록)
  B-->>S: 서버에 없는 사진
  S->>B: POST /api/models/:id/releases (manifest + model + 없는 사진들)
  B->>B: OJP1 구조 · 출력 수 · sha256 · 사진 해시 검증 → ml_model_versions
  S->>B: POST /api/models/:id/versions/N/activate
  B->>B: active_version = N → /api/detectors 카탈로그가 새 파일을 가리킴
  B-->>F: detectors_sync (식물이 있는 모든 Farmer)
  F->>B: 3.2 의 sync 경로로 새 모델 다운로드 (sha 가 바뀜)
```

### 3.6 GitHub 올리기

```mermaid
sequenceDiagram
  participant O as 운영자
  participant S as server.mjs
  participant G as lib/git.js
  participant GH as GitHub
  O->>S: POST /api/github/publish (models, message)
  S->>G: publish(ids, message, 커밋 이름/이메일)
  G->>G: git add -A -- ml/registry/모델...
  G->>G: git commit -m ... -- ml/registry/모델...  (다른 파일은 커밋 안 함)
  G->>GH: git push origin HEAD:브랜치
  G-->>S: commit · files · branch
```

### 3.7 Master 식물 상세 화면이 읽는 데이터

```mermaid
flowchart LR
  VM["PlantDetailViewModel.load(plantId)"] --> P1["GET /api/plants/plant/:id"]
  VM --> P2["GET /api/watering/:plantId"]
  VM --> P3["GET /api/events/:slaveId?days=14"]
  VM --> P4["GET /api/analysis/latest/:plantId"]
  VM --> P5["GET /api/photos/plant/:plantId"]
  VM --> P6["GET /api/videos/plant/:plantId?kind=growth"]
  VM --> P7["GET /api/detectors/plant/:plantId"]
  VM --> P8["GET /api/harvests/plant/:plantId"]
  P7 --> C1["DetectorModulesCard"]
  P3 --> C2["EventStatsCard<br/>computeEventStats"]
  P5 --> C3["GrowthAlbumCard"]
  P6 --> C4["GrowthClipCard"]
  C1 -- "지금 판독" --> CMD["POST /api/commands<br/>action=run_detectors"]
  C1 -- "관리: 추가/삭제" --> DD["POST·DELETE /api/detectors/plant/:id/:module"]
```

---

## 4. 상태 기계

### 4.1 건강등급 전이 확정 (`FarmerEngine.trackGradeTransition`)

단일 회차 오분류가 알림을 만들지 않도록, 새 (등급, 해충) 조합이 **연속 2회** 관측돼야 확정한다.

```mermaid
stateDiagram-v2
  [*] --> Stable: 첫 판독 = 확정 상태
  Stable --> Pending: 다른 등급 관측 (count 1)
  Pending --> Pending: 다른 후보 관측 (count 1 로 교체)
  Pending --> Stable: 확정 상태와 같은 관측 (후보 버림)
  Pending --> Confirmed: 같은 후보 2회째
  Confirmed --> Stable: health_change 알림 전송<br/>악화면 health_drop 판독 요청
```

### 4.2 관수 주기 (`WateringCycleTracker`)

```mermaid
stateDiagram-v2
  [*] --> Unknown
  Unknown --> Watered: onWatered
  Unknown --> Dry: dry 2회 연속 (관수 기록이 없어 주기는 안 남김)
  Watered --> Grace: 관수 후 1시간 이내
  Grace --> Grace: dry 관측은 확정에 쓰지 않음
  Grace --> Tracking: 1시간 경과
  Tracking --> Tracking: ok/wet 관측 · 단발 dry
  Tracking --> Dry: dry 2회 연속 확정
  Dry --> Watered: onWatered (주기 = dry 확정 − 관수, 최근 5개 평균)
```

### 4.3 모듈 모델 준비 상태 (`DetectorManager.prepare` → `/api/detectors/state`)

```mermaid
stateDiagram-v2
  [*] --> downloading: 서버 모델이 디스크에 없음
  [*] --> ready: 캐시된 모델 (sha 같음)
  downloading --> ready: 크기·SHA-256·출력 수 확인
  state "ready (앱 내장 모델)" as ready_bundled
  downloading --> ready_bundled: 다운로드 실패, 같은 이름의 내장 모델로 대체
  downloading --> error: 실패 + 내장 모델 없음
  [*] --> unsupported: 종 모델 없음 (색 통계로 추정)
  ready --> no_roi: 식물 RoI 없음
  unsupported --> no_roi: 식물 RoI 없음
```

---

## 5. 데이터 모델

SQLite (`backend/data/oojoo.db`). 농장·검출·모델 관련 테이블 중심 (커뮤니티·마켓·가족·결제 테이블은 생략).

```mermaid
erDiagram
  users ||--o{ slaves : "소유"
  users ||--o{ plants : "등록"
  slaves ||--o{ plants : "담당 (slave_id)"
  slaves ||--o| policies : "자율 정책"
  slaves ||--o{ commands : "명령 큐"
  plants ||--o{ events : "이벤트"
  plants ||--o{ waterings : "관수 기록"
  plants ||--o{ plant_photos : "성장 사진"
  plants ||--o{ plant_analysis : "분석 업로드"
  plants ||--o{ detector_results : "모듈 판독"
  plants ||--o{ detector_states : "모델 준비 상태"
  plants ||--o{ diagnosis_sessions : "영상 진단"
  ml_models ||--o{ ml_model_versions : "버전"
  ml_model_versions ||--o{ detector_results : "model_sha 로 버전 추적"

  plants {
    text id PK
    text user_id FK
    text slave_id FK
    text name
    text species "종 별칭으로 종 모델을 찾음"
    text roi_json "정규화 RoI x y w h"
    text detectors_json "켜진 모듈 id 와 addedAt, NULL 이면 기본 3종"
  }
  slaves {
    text id PK
    text session_key
    int online
    text stream_url "LAN MJPEG"
    text region
  }
  commands {
    text id PK
    text slave_id FK
    text action "water, capture_photo, detectors_sync, run_detectors 등"
    text status "queued, done, cancelled"
  }
  events {
    text id PK
    text type "health_change, water_low, pest_detected 등"
    text payload "JSON"
  }
  detector_results {
    text id PK
    text plant_id FK
    text module_id
    text label
    real confidence
    text trigger "periodic, run_now, watered 등"
    text model_sha
    text detail_json "관수 주기 등"
  }
  detector_states {
    text plant_id PK
    text module_id PK
    text state "downloading, ready, error, unsupported, no_roi"
  }
  ml_models {
    text id PK
    text kind "detector 또는 species"
    text labels_json "라벨 id, ko, en"
    text aliases_json "종 이름 별칭"
    int active_version "Farmer 에 배포되는 버전"
  }
  ml_model_versions {
    text id PK
    text model_id FK
    int version
    text source "bundled 또는 uploaded"
    text file "서빙 파일명"
    text sha256
    text labels_json "출력 순서"
    text metrics_json
    text dataset_json "학습/검증 사진 sha256, 라벨, 분할"
  }
```

로컬 레지스트리(`ml/registry/<model>/`)는 같은 정보를 파일로 가진다 — `model.json`(= ml_models), `versions/vN/version.json`(= ml_model_versions + 학습 이력·평가), `samples.json` + `samples/<sha256>.jpg`(학습 사진). 서버 `ml_model_versions.dataset_json` 은 업로드된 버전의 사진 목록이고, 사진 파일은 `data/ml/datasets/<model>/<sha256>.jpg`.

---

## 6. 기능 → 파일 찾기

| 기능 | Master | Farmer | 백엔드 | 로컬 도구 |
|---|---|---|---|---|
| 페어링·하트비트 | `ui/PairingScreen.kt` | `ui/PairingScreen.kt`, `FarmerEngine.start` | `routes/pairing.js` | |
| 원격 명령 (SSE) | `network/ApiService.sendCommand` | `FarmerEngine.startSSE / handleSSECommand / pollCommands` | `routes/commands.js` | |
| 검출 모듈 추가·삭제 | `ui/DetectorModules.kt`, `PlantListScreen`, `PlantDetailScreen` | `detect/DetectorManager.sync` | `routes/detectors.js`, `lib/detectors.js` | |
| 모델 다운로드·검증 | | `detect/ModelStore.kt` | `GET /api/detectors/models/:file` | |
| RoI 판독 회차 | `ui/RoiEditorScreen.kt` (RoI 지정) | `FarmerEngine.roiMonitorLoop / runRoiMonitoring / handleRoiResult`, `detect/DetectorLogic.kt` | `routes/detectors.js` results·state | |
| 관수 주기 | 상세 카드 `WateringLines` | `WateringCycleTracker`, `FarmerEngine.attemptWater` | `wateringCycle()` in `routes/detectors.js` | |
| 이벤트 통계 | `ui/EventStats.kt` | `postEventSafe`, 오프라인 큐 | `routes/events.js` (`?days=N`) | |
| 성장 사진첩·클립 | `ui/GrowthAlbum.kt`, `PlantDetailScreen.GrowthClipCard` | `album/PlantAlbum`, `vision/GrowthClipRenderer` | `routes/photos.js`, `routes/videos.js` | |
| 실시간 카메라 | `ui/LiveCameraScreen.kt` | `service/MjpegStreamer`, `FarmerEngine.startLiveRelay` | `routes/live.js` | |
| 모델 학습 | | | | `lib/train.js`, `lib/worker.js`, `lib/jobs.js` (연산: `backend/src/ml/ojp1.js`) |
| 모델 업로드·배포 | | | `routes/models.js`, `ml/registry.js` | `lib/remote.js uploadVersion` |
| 현장 성능·재학습 신호 | | | `ml/stats.js` | `server.mjs localHealth` |
| GitHub 올리기 | | | | `lib/git.js publish` |

### 테스트가 지키는 경계

| 경계 | 테스트 |
|---|---|
| Python 학습 ↔ JS forward | `ml/model-manager/test/engine.test.js` (검증 벡터), `simulation/test/species_models.test.js` |
| JS 학습 결과 ↔ Kotlin forward | `android/slave/.../detect/TrainedModelParityTest.kt` (fixture: `scripts/make-ojp1-parity-fixture.mjs`) |
| 서버 응답 ↔ 앱 DTO | `DetectorContractTest.kt`(farmer), `DetectorModulesTest.kt`(master) — 실제 백엔드로 만든 fixture |
| 검출 모듈 API | `backend/test/detectors.test.js` |
| 릴리스 업로드·배포 | `backend/test/model_releases.test.js` |
| 로컬 도구 전체 흐름 | `ml/model-manager/test/local.test.js` (실제 백엔드 + 임시 git 저장소) |
| Farmer 판독 계획·주기·스로틀 | `DetectorLogicTest.kt`, `ModelStoreTest.kt` |

---

자동 생성 그래프 다시 만들기: `node scripts/code-graph.mjs` (확인만: `--check`)
