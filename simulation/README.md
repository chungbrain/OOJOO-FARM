# OOJOO FARM — Farmer 기능 PC 시뮬레이션

Android(`android/slave`) 이식 **전에** farmer 신규 기능을 PC에서 먼저 검증하는 시뮬레이터.
기능 정의와 아키텍처는 [`docs/farmer-features.md`](../docs/farmer-features.md) 참조.

## 검증 대상 기능

| 기능 | 시나리오 | 설명 |
|---|---|---|
| F1. Jev 건강판단 | S1 | 5단계(매우건강/건강/보통/나쁨/매우나쁨) `health_grade.bin` 판정 일치율 |
| F2. 해충발생 전용 CNN | S2 | 4단계(없음/경미/보통/심각) `pest.bin` + **건강등급과의 독립성** 검증 |
| F3. 건강변화 → master 알림 | S3 | E2E — 등급 전이 → `health_change` → master SSE 실시간 + 알림 센터 |
| F4. 자동 가로/세로 촬영 | S4 | E2E — `camera_mount`(auto/landscape/portrait) → capture_photo → 사진 방향 확인 |

## 구조

```
src/
├─ models/      ojp1.js(OJP1 런타임 — PlantHealthNet.kt 포팅) · jevHealth.js · pestNet.js
│               ※ Android assets 의 실제 .bin 을 그대로 로드 (기기 == 시뮬레이터)
├─ vision/      plantScene.js(합성 식물 장면 — grade_paint.py 와 동일 분포) · png.js(경량 PNG)
├─ engine/      farmerSim.js(자율 루프 — FarmerEngine.kt 미러) · cameraSim.js · orientation.js
├─ master/      masterSim.js(페어링/명령/알림 수신 — LiveCameraScreen 미러)
└─ net/         api.js(REST+SSE 클라이언트 — ApiService.kt 미러, x-session-key 인증)
```

모델 학습: `ml/plant_health/train_health_grade.py`, `train_pest.py`
(학습이 `android/slave/app/src/main/assets/models/*.bin` + `test/vectors_*.json`을 갱신한다)

## 실행

```bat
:: 전체 시나리오 (백엔드 :4000 필요 — 없으면 자동 기동)
simulation\scripts\run-sim.bat

:: 오프라인 모델 검증만
cd simulation && node src\index.js --scenario s1,s2

:: 단위 테스트 (OJP1 round-trip / 방향 리졸버 / 전이 감지)
cd simulation && npm test
```

옵션: `--base URL`(기본 `http://127.0.0.1:4000`) · `--scenario s1,s2,s3,s4|all` · `--n 샘플수`(기본 30)

## 시나리오 세부

- **S1/S2 (오프라인)**: farmer 모니터링과 동일 경로 — upright 프레임(480×640) 렌더 → 48×48 다운샘플 →
  판정 일치율 측정. S2는 "건강 나쁨+해충 없음 → 해충없음", "건강+심각 구멍 → 심각" 등
  **등급/해충 독립성**을 별도 검증한다.
- **S3 (E2E)**: 실제 백엔드에 페어링 → 식물 등록 → farmer 자율 루프 기동(heartbeat/정책/ROI 모니터링) →
  세계 상태를 건강→보통(해충 경미)→매우나쁨(심각)→건강(회복)으로 변화 →
  master가 SSE/알림 센터로 받은 `health_change` 체인·해충 payload·최신 분석을 검증한다.
- **S4 (E2E)**: `camera_mount` 정책을 auto/landscape/portrait로 바꿔가며 capture_photo 원격 지시 →
  farmer가 자동으로 가로/세로 사진을 촬영·업로드 → master가 받은 사진의 방향·PNG mime를 검증한다.
  auto는 가상 디스플레이 회전(가로/세로 설치)에 따라 자동 선택된다.

## 통과 기준

| 시나리오 | 기준 |
|---|---|
| S1 | 전체 일치율 ≥ 85% & 알림 등급(나쁨/매우나쁨) 각 ≥ 85% & 인접 연성 등급(매우건강/건강/보통) 각 ≥ 60% & 2단계+ 오차 ≤ 2% |
| S2 | 전체 일치율 ≥ 80% & 독립성 4케이스 각 ≥ 80% |
| S3 | 전이 이벤트 ≥ 3건, 첫/끝 전이 일치, 체인 일관성, 해충 payload 동반, 알림 센터 집계, 최신 분석 반영 |
| S4 | 4 케이스(오토×2, 고정×2) 모두 사진 방향 일치 + PNG mime |
| 단위 테스트 | Python 학습 export 벡터와 JS forward logits 일치(±1e-3), 방향 수학, 전이 확정(연속 2회 관측)/플리커 방지/오프라인 큐 |

전체 통과 시 → Android 이식(docs/farmer-features.md §6 매핑 참조).
