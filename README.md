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
- **Onboarding** — Create an account (nickname + growing region) and set the backend URL on first launch
- **Dashboard** — Plant & Farmer overview, region-based weather, quick remote watering
- **Plant Management** — Register crops, track growth stages, view watering history & events
- **Farmer Management** — Device status (online/offline), autonomous policy settings, pause/resume
- **Pairing** — Generate a random 6-digit code or QR; valid for 10 minutes
- **Remote Commands** — Queue watering or mode-change instructions for offline Farmers
- **Community** — Region-based neighbor feed for **share / sell / buy** posts (crop, quantity, price), comments, reserve/complete with reputation, and report/block moderation
- **Marketplace** — Category-browsable supplies (fertilizer, seeds, valves, ESP32, sensors, recommended slave phones), search, plant-based recommendations, curated bundle kits, cart & checkout with order history, and affiliate links (CPS/CPA) for external items

### Slave (Farmer) App
- **Continuous Camera Monitoring** — CameraX live preview with periodic capture
- **On-Device AI** — Lightweight image analysis (green-ness, brightness, health status) running entirely on-device; no cloud required for decisions
- **Autonomous Watering** — Detects water stress and triggers the valve automatically, adjusted by cached weather data
- **Pest Detection & Control** — On-device insect heuristic → autonomous Fan (per policy) and Laser (auto or Master-approved) response
- **Harvest Detection** — Fruit-ripeness heuristic → harvest-ready alerts to the Master (debounced)
- **Hardware Control** — Pluggable `HardwareController` abstraction: BLE (ESP32 / Nordic UART Service) control of solenoid valve, fan, and laser with a built-in **fail-safe auto-off timer**; falls back to a simulation controller when no hardware is paired. Watering opens the valve for a volume-proportional duration.
- **Offline Resilience** — Operates autonomously for 24+ hours without network; failed event/watering reports are queued locally and **synced on reconnection**
- **Headless Mode** — Foreground service + wake lock keep the autonomous engine running with the screen off; auto-restarts after reboot (BootReceiver) and reports battery on heartbeat

### Backend
- **Account API** — Anonymous account (nickname + region) used for onboarding & weather
- Pairing authentication & **session-key auth** (slave endpoints require `x-session-key`)
- Plant, event, and watering data store (indexed for fast lookups)
- **Command queue** — Master posts commands; Slave polls & executes
- **Autonomous policy API** — Master sets per-Farmer policy (auto-water / fan / laser approval / capture interval / region); Slave syncs it on boot
- **Weather API** — Open-Meteo integration with 30-minute cache and watering-factor calculation
- **Marketplace API** — Products (categories/search), curated bundles, plant-based recommendations, affiliate click tracking, cart checkout (orders + stock decrement) & order history
- **Community API** — Region-scoped feed (share/sell/buy) with filters & search, comments, status (reserve/complete) with reputation scoring, and report/block moderation

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Mobile | Kotlin, Jetpack Compose, Material 3, Navigation Compose |
| Networking | Retrofit 2, OkHttp, kotlinx.serialization |
| Camera | CameraX (Preview, ImageCapture, ImageAnalysis) |
| Backend | Node.js, Express, SQLite (node:sqlite) |
| Weather | Open-Meteo API |
| Hardware | ESP32 via BLE (Nordic UART Service) — valve/fan/laser with fail-safe |
| AI | On-device lightweight vision (TFLite/ONNX planned; heuristic analyzer in MVP) |

---

## Project Structure

```
OOJOO-FARM/
├── prd.md                  # Product Requirements Document (v1.1.0)
├── assets/
│   ├── logo.svg            # App logo
│   ├── icon.svg            # App icon
│   └── demo.gif            # Animated prototype walkthrough
├── prototype/
│   ├── index.html          # Interactive HTML prototype (open in browser)
│   ├── DEMO.mp4            # Video walkthrough of all prototype screens
│   ├── 38758.gif           # Master app UI showcase
│   ├── 38759.gif           # Master app UI showcase
│   └── 38760.gif           # Master app UI showcase
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

## Try the Interactive Prototype

<p align="center">
  <video src="prototype/DEMO.mp4" width="400" controls muted loop autoplay alt="OOJOO FARM Prototype Demo">
    Your browser does not support the video tag. <a href="prototype/DEMO.mp4">Download the demo</a>
  </video>
</p>

Don't want to set up the whole development environment? You can experience OOJOO FARM right now in your browser.

The project ships with a **fully interactive HTML prototype** that simulates both the Master and Slave (Farmer) apps on realistic phone frames. No build tools, no emulator — just open the file and tap around.

The video above shows a walkthrough of all screens — Master app (splash, onboarding, home dashboard, plant detail, AI scan, remote watering, notifications, harvest alert, pest detection, pairing, settings, marketplace, listing, chat, profile, tools) and Slave/Farmer app (splash, intro, pairing, camera guide, hardware pairing, autonomous dashboard, watering event).

### Master App UX/UI Showcase

<p align="center">
  <img src="prototype/38758.gif" width="280" alt="Master App UI - Home & Plant Management" />
  &nbsp;
  <img src="prototype/38759.gif" width="280" alt="Master App UI - Alerts & Pairing" />
  &nbsp;
  <img src="prototype/38760.gif" width="280" alt="Master App UI - Community & Marketplace" />
</p>

<p align="center"><sub>Master app screens: dashboard, plant monitoring, remote watering, alerts, pairing, and community marketplace</sub></p>

### How to Open

Open `prototype/index.html` directly in any modern browser:

```
prototype/index.html
```

Or clone the repo and double-click the file. That's it.

### What You Can Explore

The prototype lets you switch between two simulated devices with a toggle at the top:

#### Master App (green theme)
| Screen | What to try |
|--------|-------------|
| **Onboarding** | Swipe through the 3-step intro — "Command from your phone", "Pair with a code", "Farmer manages autonomously" |
| **Home Dashboard** | View weather card, Farmer device status, plant carousel, quick-water button, recent alerts (harvest & pest) |
| **Plant Detail** | Tap any plant to see AI analysis results, growth stats (fruit count, ripeness %), remote control panel, and event log |
| **AI Scan Result** | View a simulated camera capture with on-device AI detection (water stress, fruit count, pest detection) |
| **Remote Watering** | Press "Water now" and watch the progress bar as the command is sent to the Farmer |
| **Harvest Alert** | See the harvest-ready notification with ripeness indicators and "mark as harvested" action |
| **Pest Detection** | View the autonomous Fan response log and manual Laser override option |
| **Pairing** | Generate a 6-digit pairing code or QR for connecting a Farmer device |
| **Settings** | Manage Farmer devices, autonomous policies (water auto / Fan auto / Laser approval), notification preferences |
| **Marketplace** *(Phase 3-4 preview)* | Browse nearby crop sharing/selling listings, categories, affiliate tool shop, write a post, chat with a neighbor, view profiles with reputation |
| **Notifications** | Full notification center with harvest, pest, watering, and reconnection events |

#### Slave / Farmer App (teal theme)
| Screen | What to try |
|--------|-------------|
| **Onboarding** | "On-device AI autonomous management", "Pair via code", "Direct hardware control" |
| **Pairing** | Type the 6-digit code on the on-screen keypad, or scan QR to connect |
| **Camera Guide** | See the live camera frame with alignment guide and plant recognition confidence |
| **Hardware Pairing** | Watch BLE scan find ESP32 modules (water valve, fan, laser), then connect |
| **Autonomous Dashboard** | Live camera preview, on-device AI status (water stress, fruit detection, insect detection, AI decision), autonomous policy table, last watering info, action log, headless mode toggle, connection/battery status |
| **Autonomous Watering Event** | Trigger a watering action and watch the valve progress bar with fail-safe timer |

> **Tip:** The prototype uses mock data and simulated animations — it reflects the intended UX, not live backend data. It's the same prototype used during product design review.

<p align="center">
  <img src="assets/icon.svg" width="48" /> &nbsp; <em>Open the prototype and switch between Master and Farmer to see the full flow.</em>
</p>

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
| **Phase 1 — MVP** | Pairing, camera capture, on-device analysis, autonomous watering, command queue, weather | ✅ Done |
| **Phase 2** | Harvest/pest detection, Fan/Laser control, BLE hardware, offline sync, reports | ✅ Done |
| **Phase 3** | Location-based community (share/sell/buy), comments, reputation, moderation | ✅ Done |
| **Phase 4** | Marketplace, cart/checkout, affiliate links, subscription plans | ✅ Done |
| Phase 5 | AI model refinement (TFLite), FCM push, multi-Farmer/crop refinement | Partial — multi-Farmer/crop supported; TFLite & FCM need model file / Firebase config |

> Heuristic on-device analysis stands in for TFLite models; FCM push requires a Firebase `google-services.json`.

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
