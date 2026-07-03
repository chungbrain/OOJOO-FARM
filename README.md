<p align="center">
  <img src="assets/icon.svg" width="160" alt="OOJOO FARM Logo" />
</p>

<h1 align="center">OOJOO FARM</h1>

<p align="center">
  <strong>Grow. Care. Share. Harvest.</strong><br/>
  A two-device Android system that lets anyone grow edible crops at home — autonomously.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material3-42A5F5?logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Node.js-Express-339933?logo=node.js&logoColor=white" />
  <img src="https://img.shields.io/badge/status-Phase%201%20MVP-orange" />
</p>

---

## What is OOJOO FARM?

OOJOO FARM is a home edible-crop growing system built around a simple idea:

> **Your phone tells it what to grow. A spare phone in front of the plant grows it.**

Instead of expensive IoT controllers, OOJOO FARM re-purposes any old or low-cost Android phone as a dedicated **Farmer** — placed next to your plants, running an on-device AI that watches, waters, and alerts you when it's time to harvest. You keep your main phone in your pocket and monitor everything from the **Master** app.

### Two Apps, One Garden

| App | Installed On | Role |
|-----|-------------|------|
| **Master** | Your personal phone | Monitor, remote-control, receive alerts, community & marketplace |
| **Slave (Farmer)** | A fixed device next to plants | Continuous camera monitoring, on-device AI, autonomous watering, hardware control |

The two devices are linked by a randomly generated pairing code — no accounts, no complex setup.

---

## Why It Matters for the Community

Home-grown food is more than a hobby — it's a step toward **food security, sustainability, and stronger neighborhoods**.

OOJOO FARM lowers the barrier to growing your own vegetables, herbs, and fruits. Not everyone has the time to water plants every day or the knowledge to spot pests and disease early. By automating the hard parts with a phone you already own, we make fresh, safe, home-grown produce accessible to **families, beginners, and busy professionals alike**.

Beyond the individual garden, OOJOO FARM is designed to build **local growing communities**:

- **Share your harvest** — Post surplus vegetables to neighbors within a few kilometers. Reduce food waste, build relationships.
- **Learn together** — Regional feeds let growers swap tips, show off their crops, and help newcomers succeed.
- **Reuse, don't discard** — That old Android phone in your drawer becomes a diligent gardener instead of e-waste.
- **Know your food** — When you grow it yourself, you know exactly what's on your plate: no mystery pesticides, no long supply chains, just sunlight, water, and care.

Every garden on OOJOO FARM is a small act of self-reliance. Together, those gardens add up to greener, healthier, more connected communities.

---

## Architecture

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│      Master App          │         │     Slave App (Farmer)        │
│  (Your phone)            │  Pair   │  (Fixed device at plant)       │
│                          │  Code   │                              │
│  · Dashboard / Remote    │ ◀─────▶ │  · Continuous Camera Capture  │
│  · Harvest / Pest Alerts │  Sync   │  · On-Device AI (Autonomous)  │
│  · Community / Market    │         │  · Auto Water / Pest Control  │
│  · Farmer Pairing        │         │  · Hardware Control (BLE)     │
└─────────────────────────┘         └──────────────┬───────────────┘
        ▲                                            │ BLE / Wi-Fi
        │ Push / Status                               │
        │                                            ▼
   ┌────┴────┐                            ┌────────────────────┐
    │  Cloud   │  Weather API, Accounts,    │ External Hardware   │
    │  Backend │  Community, Market         │ (Valve/Fan/Laser)   │
    └─────────┘                            └────────────────────┘
```

---

## Key Features

### Master App
- **Dashboard** — Plant & Farmer overview, weather, quick remote watering
- **Plant Management** — Register crops, track growth stages, view watering history & events
- **Farmer Management** — Device status (online/offline), autonomous policy settings, pause/resume
- **Pairing** — Generate a random 6-digit code or QR; valid for 10 minutes
- **Remote Commands** — Queue watering or mode-change instructions for offline Farmers
- **Community** *(Phase 3)* — Location-based crop sharing, posts, reputation
- **Marketplace** *(Phase 4)* — Gardening supplies, recommended slave phones, affiliate links

### Slave (Farmer) App
- **Continuous Camera Monitoring** — CameraX live preview with periodic capture
- **On-Device AI** — Lightweight image analysis (green-ness, brightness, health status) running entirely on-device; no cloud required for decisions
- **Autonomous Watering** — Detects water stress and triggers the valve automatically, adjusted by cached weather data
- **Pest Detection & Control** *(Phase 2)* — Insect detection with Fan/Laser response
- **Harvest Detection** *(Phase 2)* — Fruit tracking with push notifications to the Master
- **Hardware Control** *(Phase 2)* — Direct BLE/Wi-Fi control of solenoid valves, fans, and laser modules
- **Offline Resilience** — Operates autonomously for 24+ hours without network; syncs events on reconnection
- **Headless Mode** — Screen off, foreground service, auto-restart on error

### Backend
- Pairing authentication & session management
- Plant, event, and watering data store
- **Command queue** — Master posts commands; Slave polls & executes
- **Weather API** — Open-Meteo integration with 30-minute cache and watering-factor calculation
- Community & marketplace *(planned)*

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Mobile | Kotlin, Jetpack Compose, Material 3, Navigation Compose |
| Networking | Retrofit 2, OkHttp, kotlinx.serialization |
| Camera | CameraX (Preview, ImageCapture, ImageAnalysis) |
| Backend | Node.js, Express, SQLite (node:sqlite) |
| Weather | Open-Meteo API |
| Hardware | ESP32 via BLE/Wi-Fi *(planned)* |
| AI | On-device lightweight vision (TFLite/ONNX planned; heuristic analyzer in MVP) |

---

## Project Structure

```
OOJOO-FARM/
├── prd.md                  # Product Requirements Document (v1.1.0)
├── assets/
│   ├── logo.svg            # App logo
│   └── icon.svg            # App icon
├── prototype/
│   └── index.html          # UX/UI prototype
├── backend/                # Node.js + Express + SQLite
│   └── src/
│       ├── server.js
│       ├── db.js
│       └── routes/
│           ├── pairing.js
│           ├── plants.js
│           ├── events.js
│           ├── watering.js
│           ├── commands.js
│           └── weather.js
└── android/                # Multi-module Android project
    ├── master/             # Master app (com.oojoo.farm.master)
    │   └── app/src/main/java/com/oojoo/farm/master/
    │       ├── MainActivity.kt
    │       ├── model/      # Data models
    │       ├── network/    # Retrofit API client
    │       └── ui/         # Home, PlantList, PlantDetail, PlantRegistration,
    │                      #   FarmerList, Pairing screens
    └── slave/              # Slave/Farmer app (com.oojoo.farm.slave)
        └── app/src/main/java/com/oojoo/farm/slave/
            ├── MainActivity.kt
            ├── data/       # SharedPreferences (Prefs)
            ├── model/      # Data models
            ├── network/    # Retrofit API client
            ├── vision/     # CameraX preview + PlantAnalyzer
            └── ui/         # Pairing, Dashboard, Settings screens
```

---

## Getting Started

### Backend

```bash
cd backend
cp .env.example .env
npm install
npm start          # http://localhost:4000
```

For development with auto-reload: `npm run dev`

### Android

1. Open **Android Studio** → **Open** → select the `android/` folder
2. Wait for Gradle Sync to complete
3. Select **app (master)** or **app (slave)** from the run configuration dropdown
4. Press **Run** (Shift+F10)

> The backend URL defaults to `http://10.0.2.2:4000/` for emulators.
> For a physical device, change it in the Slave pairing screen or `ApiClient.kt`.

---

## Roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| **Phase 1 — MVP** | Pairing, camera capture, on-device analysis, autonomous watering, command queue, weather | In Progress |
| Phase 2 | Harvest detection, pest detection, Fan/Laser control, BLE hardware | Planned |
| Phase 3 | Location-based community, sharing, reputation | Planned |
| Phase 4 | Marketplace, payments, affiliate links | Planned |
| Phase 5 | AI model refinement, multi-Farmer, subscriptions | Planned |

See [`prd.md`](prd.md) for the full product requirements document.

---

## License

Proprietary — WOOJU INDUSTRY. All rights reserved.

---

<p align="center">
  <em>"The master phone commands. The Farmer phone grows. Anyone can plant, grow, share, and harvest — at home."</em>
</p>

<p align="center">
  Built by <strong>WOOJU INDUSTRY</strong>
</p>
