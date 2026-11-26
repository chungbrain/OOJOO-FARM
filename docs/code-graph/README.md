# OOJOO FARM code graph

> As of 2026-11-26. A map, drawn as graphs, of "what calls what and where the data flows" at the code level.
> Diagrams are Mermaid — GitHub renders them directly. 한국어: [README_kor.md](README_kor.md)
>
> - **This document**: hand-written structure, flows, state machines and data model (file and function names match the code)
> - **[generated.md](generated.md)**: module dependency graphs and the API map built from the sources by `node scripts/code-graph.mjs` (regenerate after changing code)

## Contents

1. [System at a glance](#1-system-at-a-glance)
2. [Component maps](#2-component-maps)
3. [Key flows](#3-key-flows) — pairing and commands / adding a detector module / RoI reading round / watering event / model-manager training and deployment / GitHub / plant detail screen
4. [State machines](#4-state-machines)
5. [Data model](#5-data-model)
6. [Feature → file lookup](#6-feature--file-lookup)

---

## 1. System at a glance

```mermaid
flowchart LR
  subgraph phone["User's phone"]
    M["Master app<br/>android/master"]
  end
  subgraph farm["Next to the plants"]
    F["Farmer app<br/>android/slave"]
    HW["ESP32<br/>valve · fan · laser"]
  end
  subgraph cloud["GCP VM 34.50.40.104:4000"]
    B["Backend<br/>backend/src (Express + SQLite)"]
    DB[("oojoo.db")]
    FS[("data/<br/>photos · videos · ml")]
  end
  subgraph pc["Operator PC"]
    MM["Local model-manager<br/>ml/model-manager :4900"]
    REG[("ml/registry/")]
  end
  GH[("GitHub<br/>chungbrain/OOJOO-FARM")]

  M -- "REST + SSE (live notifications)" --> B
  F -- "REST + SSE (commands)<br/>x-session-key" --> B
  F -- "BLE (Nordic UART)" --> HW
  M -. "LAN MJPEG :8613" .-> F
  B --- DB
  B --- FS
  MM -- "release upload · activate<br/>x-admin-token" --> B
  MM --- REG
  MM -- "git commit + push<br/>ml/registry/model" --> GH
```

| Component | Entry point | Role |
|---|---|---|
| Master app | `MainActivity.kt` (Navigation Compose routes) | The user's phone — manage plants and Farmers, remote commands, notifications, community and market |
| Farmer app | `service/FarmerService.kt` → `service/FarmerEngine.kt` | A phone fixed next to the plants — camera readings, autonomous watering and pest response, hardware control |
| Backend | `server.js` → `app.js` | Accounts, pairing, plants, events, photos, command queue, detector module catalog, model registry |
| model-manager | `ml/model-manager/server.mjs` | Operator PC — labels, training photos, training and evaluation, upload to the server and GitHub |

---

## 2. Component maps

### 2.1 Farmer app — centered on `FarmerEngine`

`FarmerEngine` (a singleton `object`) runs every loop. The UI (`DashboardScreen`) and the background service (`FarmerService`) share the same instance.

```mermaid
flowchart TB
  SVC["FarmerService<br/>(foreground service, BootReceiver)"] --> ENG

  subgraph ENG["FarmerEngine"]
    direction TB
    L1["Main loop, 30 s<br/>heartbeat · flushQueue · pollCommands (when SSE is down)<br/>tick(): full-frame auto watering / harvest / pests<br/>every 10 ticks: syncPolicy() + refreshPlants()"]
    L2["Weather loop, 30 min<br/>refreshFarmLocation · fetchWeather"]
    L3["roiMonitorLoop()<br/>period (roi_interval) + event wait<br/>→ runRoiMonitoring(periodic)"]
    L4["startSSE()<br/>/api/commands/sse/slave → handleSSECommand"]
    L5["startLiveRelay()<br/>uploads a frame every 3 s (live_start ~ stop)"]
  end

  ENG --> DM["detect/DetectorManager<br/>sync · run · report"]
  DM --> MS["detect/ModelStore<br/>download + SHA-256 check"]
  DM --> LOGIC["detect/DetectorLogic<br/>DetectorPlanner · ReportGate · WateringCycleTracker"]
  DM --> OJP["vision/Ojp1<br/>parse · forward"]
  L3 --> PA["vision/PlantAnalyzer<br/>cropRoi · analyzeCrop"]
  PA --> PHN["vision/PlantHealthNet<br/>6 classes per species"]
  L3 --> CH["vision/CameraHolder<br/>captureFrame"]
  ENG --> HWC["hardware/Hardware.controller<br/>BleHardwareController | Mock"]
  ENG --> API["network/ApiClient.api<br/>(Retrofit ApiService)"]
  ENG --> ALB["album/PlantAlbum<br/>vision/GrowthClipRenderer"]
  ENG --> MJ["service/MjpegStreamer :8613"]
  ENG --> RS["vision/RoiStore<br/>files/rois.json"]
```

### 2.2 Master app — screens · ViewModels · API

Each screen's ViewModel calls `ApiClient.api` (Retrofit). Shared state lives in `data/` (Session, Prefs, AppLocale); every DTO is in one file, `model/Models.kt`.

```mermaid
flowchart LR
  NAV["MainActivity<br/>NavHost routes"] --> HOME["HomeScreen<br/>farm view · weather"]
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
  APIM --> BK[("Backend /api/*")]
```

### 2.3 Backend — routers and shared modules

`app.js` mounts 26 routers at `/api/<name>`. Detector modules and the model registry are handled by `lib/detectors.js` + `ml/`. (Full import graph: [generated.md §1](generated.md#1-backend))

```mermaid
flowchart LR
  APP["app.js"] --> R1["routes/plants.js"]
  APP --> R2["routes/detectors.js"]
  APP --> R3["routes/models.js"]
  APP --> R4["routes/commands.js<br/>SSE hub"]
  APP --> R5["routes/events.js"]
  APP --> RX["users · pairing · watering · photos · videos · live ·<br/>analysis · diagnosis · policy · weather · community · market ..."]

  R1 --> LD["lib/detectors.js<br/>catalog() · plantDetectors()"]
  R2 --> LD
  R2 --> R4
  R1 --> R2
  R3 --> R2
  R3 --> REGB["ml/registry.js<br/>deployed versions · aliases"]
  R3 --> ST["ml/stats.js<br/>field performance · retraining signals"]
  R3 --> OJ["ml/ojp1.js<br/>upload validation"]
  LD --> REGB
  ST --> LD
  REGB --> DEFS["ml/defs.js<br/>built-in model definitions"]
  R5 --> R4
  R5 --> PUSH["lib/push.js (FCM)"]
```

### 2.4 Local model-manager

```mermaid
flowchart LR
  UI["ui/index.html<br/>list · performance · training data · train & deploy"] -- "fetch /api/* + x-mm-local" --> SRV["server.mjs<br/>createApp()"]
  SRV --> ST["lib/store.js<br/>reads/writes ml/registry files"]
  SRV --> JB["lib/jobs.js<br/>job queue"]
  JB --> WK["lib/worker.js<br/>(worker thread)"]
  WK --> TR["lib/train.js<br/>trainModel · evaluate · augment"]
  WK --> IMG["lib/image.js<br/>JPEG · EXIF · RoI crop"]
  TR --> OJ["backend/src/ml/ojp1.js<br/>forward · backward · adaptModel"]
  ST --> DEFS["backend/src/ml/defs.js"]
  SRV --> RM["lib/remote.js<br/>server queries · uploadVersion"]
  SRV --> GT["lib/git.js<br/>status · publish"]
  RM --> BK[("GCP backend /api/models")]
  GT --> GH[("GitHub")]
```

---

## 3. Key flows

### 3.1 Pairing → heartbeat → commands

```mermaid
sequenceDiagram
  autonumber
  participant M as Master app
  participant B as Backend
  participant F as Farmer app
  M->>B: POST /api/pairing/code (userId)
  B-->>M: 6-digit code (valid for 10 min)
  F->>B: POST /api/pairing/verify (code)
  B-->>F: slaveId + sessionKey
  Note over F: saved in Prefs, x-session-key on every later request
  loop every 30 s (FarmerEngine main loop)
    F->>B: POST /api/pairing/heartbeat (battery, stream_url, location)
  end
  F->>B: GET /api/commands/sse/slave/:slaveId (SSE kept open)
  M->>B: POST /api/commands (action=water etc.)
  B->>B: stored as queued in the commands table
  B-->>F: SSE data: command
  F->>F: handleSSECommand → attemptWater / capture / ...
  F->>B: POST /api/commands/:id/done
  Note over F,B: if SSE drops, the main loop polls GET /api/commands/pending
```

### 3.2 Adding a detector module → the Farmer fetches the model

```mermaid
sequenceDiagram
  autonumber
  participant U as User
  participant M as Master<br/>PlantListScreen / PlantDetailScreen
  participant B as Backend<br/>routes/detectors.js
  participant F as Farmer<br/>FarmerEngine
  participant D as DetectorManager
  participant S as ModelStore
  U->>M: plant card 🧠 AI n → Pest detection [Add]
  M->>B: POST /api/detectors/plant/:plantId/pest
  B->>B: add to plants.detectors_json
  B->>B: queueDetectorSync() — reuses a detectors_sync that is still queued
  B-->>F: SSE command detectors_sync
  B-->>M: the plant's module list (enabled, state, latest)
  F->>F: refreshPlants()
  F->>B: GET /api/plants/slave/:slaveId (plants[].detectors, roi)
  F->>D: sync(slaveId, plants, roiPlantIds)
  D->>B: GET /api/detectors (catalog: model url · sha256 · labels · speciesAliases)
  D->>B: POST /api/detectors/state (downloading)
  D->>S: ensure(ModelRef)
  S->>B: GET /api/detectors/models/pest-v2.bin
  S->>S: check size · SHA-256 → files/detector_models/(sha).bin
  D->>D: Ojp1.parse → check output count = label count → models[plant,module]
  D->>B: POST /api/detectors/state (ready | no_roi | unsupported | error)
  D->>D: planner.request(module_added, plant, pest)
  Note over D: the next RoI reading round wakes up at once and reads (3.3)
```

Removal takes the same path: `DELETE /api/detectors/plant/:plantId/:moduleId` → `detectors_sync` → `sync()` drops the module from `models` and `latest`, and `ModelStore.retainOnly()` deletes model files that are no longer used.

### 3.3 RoI reading round (periodic + events)

```mermaid
sequenceDiagram
  autonumber
  participant L as roiMonitorLoop
  participant P as DetectorPlanner
  participant C as CameraHolder
  participant A as PlantAnalyzer
  participant D as DetectorManager
  participant E as FarmerEngine<br/>handleRoiResult
  participant B as Backend
  L->>P: waitMs(now, lastPeriodicAt, roi_interval)
  Note over L: waits for the next period or the earliest event request — the roiWake channel wakes it at once
  L->>P: plan(now, enabled modules (plants with an RoI), periodic)
  P-->>L: plant → (module → triggers)
  L->>C: captureFrame()
  loop for each RoI
    L->>A: cropRoi(frame, roi) → analyzeCrop (color stats + species diagnosis)
    L->>D: run(plantId, module→triggers, crop, needWater)
    D->>D: softmax(forward) → label (watering: waterLabelFromProbs + WateringCycleTracker.observe)
    D-->>L: fresh readings
    L->>E: handleRoiResult(roi, base, fresh, triggers)
    E->>B: POST /api/analysis/report (5 min throttle, 1 min when abnormal)
    E->>E: trackGradeTransition — health_change after 2 in a row
    E->>B: POST /api/events (health_change · water_low · pest_detected)
    E->>E: watering module says dry → attemptWater (30 min cooldown)
    E->>D: report(slaveId, plantId, fresh, triggers)
    D->>B: POST /api/detectors/results (ReportGate: immediate on a label change or event, otherwise every 5 min)
  end
  B-->>B: saved to detector_results + master SSE detector_result
```

Event triggers (`Triggers`): `module_added` · `run_now` (Master "Read now" → `run_detectors` command) · `watered` / `watered_followup` (+30 min) · `pest_response` (fan/laser, +1 min) · `health_drop` (confirmed grade drop) · `roi_changed`.

### 3.4 Watering → watering cycle record

```mermaid
sequenceDiagram
  autonumber
  participant E as FarmerEngine
  participant H as Hardware.controller
  participant D as DetectorManager
  participant W as WateringCycleTracker
  participant B as Backend
  E->>H: openValve(amount × 20 ms, max 60 s)
  alt valve opened (BLE connected or simulation)
    E->>B: POST /api/watering/log + POST /api/events (auto_water | manual_water)
    E->>D: onWatered(plantId)
    D->>W: onWatered — reset the confirmed state, record lastWateredAt
    E->>D: request(watered, watering + health) / request(watered_followup, watering, +30 min)
  else hardware not connected · error
    E->>B: POST /api/events (water_failed)
  end
  Note over W: later, two dry readings in a row taken more than 1 h after watering<br/>cycle = time dry was confirmed − watering time (mean of the last 5 → next watering estimate)
```

### 3.5 model-manager: photos → training → server deployment

```mermaid
sequenceDiagram
  autonumber
  participant O as Operator (browser)
  participant S as server.mjs
  participant R as store.js<br/>ml/registry
  participant J as jobs.js + worker.js
  participant B as GCP backend<br/>routes/models.js
  participant F as Farmers
  O->>S: upload photos + labels / import Farmer photos
  S->>B: GET /api/models/:id/candidates (plant RoI, photo list)
  S->>B: download the original photo → image.js cropNormalized(RoI)
  S->>R: addSample — 256 px JPEG named by sha256, train/val split (by group)
  O->>S: POST /api/models/:id/train (start version, epochs, learning rate)
  S->>J: enqueue(train)
  J->>J: worker: adaptModel(parent, labels) → trainModel(L2-SP) → evaluate(new model, parent)
  J->>R: saveTrainedVersion → versions/vN/model.bin + version.json (metrics, baseline, dataset)
  O->>S: version vN [upload + deploy]
  S->>B: POST /api/models/:id/datasets/missing (sha256 list)
  B-->>S: photos the server does not have
  S->>B: POST /api/models/:id/releases (manifest + model + missing photos)
  B->>B: validate OJP1 structure · output count · sha256 · photo hashes → ml_model_versions
  S->>B: POST /api/models/:id/versions/N/activate
  B->>B: active_version = N → the /api/detectors catalog points to the new file
  B-->>F: detectors_sync (every Farmer that has plants)
  F->>B: downloads the new model through the sync path of 3.2 (the sha changed)
```

### 3.6 Publishing to GitHub

```mermaid
sequenceDiagram
  participant O as Operator
  participant S as server.mjs
  participant G as lib/git.js
  participant GH as GitHub
  O->>S: POST /api/github/publish (models, message)
  S->>G: publish(ids, message, commit name/email)
  G->>G: git add -A -- ml/registry/model...
  G->>G: git commit -m ... -- ml/registry/model...  (no other files are committed)
  G->>GH: git push origin HEAD:branch
  G-->>S: commit · files · branch
```

### 3.7 Data read by the Master plant detail screen

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
  C1 -- "Read now" --> CMD["POST /api/commands<br/>action=run_detectors"]
  C1 -- "Manage: add/remove" --> DD["POST·DELETE /api/detectors/plant/:id/:module"]
```

---

## 4. State machines

### 4.1 Confirming a health-grade transition (`FarmerEngine.trackGradeTransition`)

So that a single misclassified round does not raise a notification, a new (grade, pest) combination is confirmed only after it is observed **twice in a row**.

```mermaid
stateDiagram-v2
  [*] --> Stable: first reading = confirmed state
  Stable --> Pending: a different grade observed (count 1)
  Pending --> Pending: another candidate observed (replaces it, count 1)
  Pending --> Stable: observation equals the confirmed state (candidate dropped)
  Pending --> Confirmed: same candidate a second time
  Confirmed --> Stable: send the health_change notification<br/>if worse, request a health_drop reading
```

### 4.2 Watering cycle (`WateringCycleTracker`)

```mermaid
stateDiagram-v2
  [*] --> Unknown
  Unknown --> Watered: onWatered
  Unknown --> Dry: dry twice in a row (no watering record, so no cycle is stored)
  Watered --> Grace: within 1 h of watering
  Grace --> Grace: dry readings are not used for confirmation
  Grace --> Tracking: 1 h elapsed
  Tracking --> Tracking: ok/wet readings · a single dry
  Tracking --> Dry: dry confirmed twice in a row
  Dry --> Watered: onWatered (cycle = dry confirmed − watered, mean of the last 5)
```

### 4.3 Module model readiness (`DetectorManager.prepare` → `/api/detectors/state`)

```mermaid
stateDiagram-v2
  [*] --> downloading: server model not on disk
  [*] --> ready: cached model (same sha)
  downloading --> ready: size · SHA-256 · output count checked
  state "ready (bundled app model)" as ready_bundled
  downloading --> ready_bundled: download failed, falls back to the bundled model of the same name
  downloading --> error: failed + no bundled model
  [*] --> unsupported: no species model (estimated from color stats)
  ready --> no_roi: the plant has no RoI
  unsupported --> no_roi: the plant has no RoI
```

---

## 5. Data model

SQLite (`backend/data/oojoo.db`). Focused on the farm, detector and model tables (community, market, family and payment tables are omitted).

```mermaid
erDiagram
  users ||--o{ slaves : "owns"
  users ||--o{ plants : "registers"
  slaves ||--o{ plants : "tends (slave_id)"
  slaves ||--o| policies : "autonomy policy"
  slaves ||--o{ commands : "command queue"
  plants ||--o{ events : "events"
  plants ||--o{ waterings : "watering log"
  plants ||--o{ plant_photos : "growth photos"
  plants ||--o{ plant_analysis : "analysis uploads"
  plants ||--o{ detector_results : "module readings"
  plants ||--o{ detector_states : "model readiness"
  plants ||--o{ diagnosis_sessions : "video diagnosis"
  ml_models ||--o{ ml_model_versions : "versions"
  ml_model_versions ||--o{ detector_results : "version tracked by model_sha"

  plants {
    text id PK
    text user_id FK
    text slave_id FK
    text name
    text species "species aliases resolve the species model"
    text roi_json "normalized RoI x y w h"
    text detectors_json "enabled module ids and addedAt, NULL means the default 3"
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
    text action "water, capture_photo, detectors_sync, run_detectors, etc."
    text status "queued, done, cancelled"
  }
  events {
    text id PK
    text type "health_change, water_low, pest_detected, etc."
    text payload "JSON"
  }
  detector_results {
    text id PK
    text plant_id FK
    text module_id
    text label
    real confidence
    text trigger "periodic, run_now, watered, etc."
    text model_sha
    text detail_json "watering cycle etc."
  }
  detector_states {
    text plant_id PK
    text module_id PK
    text state "downloading, ready, error, unsupported, no_roi"
  }
  ml_models {
    text id PK
    text kind "detector or species"
    text labels_json "label id, ko, en"
    text aliases_json "species name aliases"
    int active_version "version deployed to Farmers"
  }
  ml_model_versions {
    text id PK
    text model_id FK
    int version
    text source "bundled or uploaded"
    text file "served file name"
    text sha256
    text labels_json "output order"
    text metrics_json
    text dataset_json "train/val photo sha256, label, split"
  }
```

The local registry (`ml/registry/<model>/`) holds the same information as files — `model.json` (= ml_models), `versions/vN/version.json` (= ml_model_versions + training history and evaluation), `samples.json` + `samples/<sha256>.jpg` (training photos). On the server, `ml_model_versions.dataset_json` is the photo list of an uploaded version, and the photo files live in `data/ml/datasets/<model>/<sha256>.jpg`.

---

## 6. Feature → file lookup

| Feature | Master | Farmer | Backend | Local tool |
|---|---|---|---|---|
| Pairing · heartbeat | `ui/PairingScreen.kt` | `ui/PairingScreen.kt`, `FarmerEngine.start` | `routes/pairing.js` | |
| Remote commands (SSE) | `network/ApiService.sendCommand` | `FarmerEngine.startSSE / handleSSECommand / pollCommands` | `routes/commands.js` | |
| Adding/removing detector modules | `ui/DetectorModules.kt`, `PlantListScreen`, `PlantDetailScreen` | `detect/DetectorManager.sync` | `routes/detectors.js`, `lib/detectors.js` | |
| Model download · verification | | `detect/ModelStore.kt` | `GET /api/detectors/models/:file` | |
| RoI reading round | `ui/RoiEditorScreen.kt` (sets the RoI) | `FarmerEngine.roiMonitorLoop / runRoiMonitoring / handleRoiResult`, `detect/DetectorLogic.kt` | `routes/detectors.js` results · state | |
| Watering cycle | detail card `WateringLines` | `WateringCycleTracker`, `FarmerEngine.attemptWater` | `wateringCycle()` in `routes/detectors.js` | |
| Event statistics | `ui/EventStats.kt` | `postEventSafe`, offline queue | `routes/events.js` (`?days=N`) | |
| Growth album · clips | `ui/GrowthAlbum.kt`, `PlantDetailScreen.GrowthClipCard` | `album/PlantAlbum`, `vision/GrowthClipRenderer` | `routes/photos.js`, `routes/videos.js` | |
| Live camera | `ui/LiveCameraScreen.kt` | `service/MjpegStreamer`, `FarmerEngine.startLiveRelay` | `routes/live.js` | |
| Model training | | | | `lib/train.js`, `lib/worker.js`, `lib/jobs.js` (math: `backend/src/ml/ojp1.js`) |
| Model upload · deployment | | | `routes/models.js`, `ml/registry.js` | `lib/remote.js uploadVersion` |
| Field performance · retraining signals | | | `ml/stats.js` | `server.mjs localHealth` |
| Publishing to GitHub | | | | `lib/git.js publish` |

### Boundaries guarded by tests

| Boundary | Tests |
|---|---|
| Python training ↔ JS forward | `ml/model-manager/test/engine.test.js` (reference vectors), `simulation/test/species_models.test.js` |
| JS training output ↔ Kotlin forward | `android/slave/.../detect/TrainedModelParityTest.kt` (fixture: `scripts/make-ojp1-parity-fixture.mjs`) |
| Server responses ↔ app DTOs | `DetectorContractTest.kt` (farmer), `DetectorModulesTest.kt` (master) — fixtures produced by the real backend |
| Detector module API | `backend/test/detectors.test.js` |
| Release upload · activation | `backend/test/model_releases.test.js` |
| Local tool end to end | `ml/model-manager/test/local.test.js` (real backend + temporary git repository) |
| Farmer reading plan · cycle · throttling | `DetectorLogicTest.kt`, `ModelStoreTest.kt` |

---

Regenerate the generated graphs: `node scripts/code-graph.mjs` (check only: `--check`)
