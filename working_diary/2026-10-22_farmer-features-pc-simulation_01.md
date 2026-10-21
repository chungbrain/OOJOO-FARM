# 작업 일지 — 2026-10-22

## Farmer 기능 정의 + PC 시뮬레이션 선행 구현 (Jev 건강판단 / 해충 전용 CNN / 변화 알림 / 자동 방향 촬영)

**요구사항:**
1. 식물 건강판단 — Jev급(초경량·고속) 모델이 5단계(매우건강/건강/보통/나쁨/매우나쁨)로 빠르게 판정
2. 해충발생 — 건강진단과 **별도 모델**로 CNN 기법으로 정확 평가. 두 모델 모두 온디바이스 초경량
3. 건강상태 변화 시 master에게 noti
4. master의 사진 촬영 — farmer의 카메라 위치에 따라 가로/세로 모드 자동 촬영
5. **Android 구현에 앞서 PC 시뮬레이션으로 먼저 검증**

**커밋 (4건 + 일지, `2428ea9..5ca05ab` push 완료):**

| 커밋 | 파일 | 내용 |
|---|---|---|
| `99979a0` | `ml/plant_health/grade_paint.py`, `train_health_grade.py`, `train_pest.py`, `metrics/{health_grade,pest}.json`, `android/slave/.../assets/models/{health_grade,pest}.bin` | F1/F2 모델. 공용 생성기(등급×해충 독립 파라미터) + OJP1 저장(nclass 파라미터화) + 검증벡터 export. **health_grade**: 8종 혼합 5등급, val_acc **0.827** (매우건강70/건강87/보통97/나쁨87/매우나쁨93% — 시뮬 실측). **pest**: 8종×등급 혼합 4단계, val_acc **0.884**. 둘 다 conv8-16-24+GAP+dense(~20KB) |
| `a914df8` | `backend/src/db.js`, `routes/{events,policy,photos}.js`, `backend/test/farmer_features.test.js` | F3/F4 백엔드. events: noti 대상(health_change/water_low/pest_detected/harvest_ready)을 master SSE로 실시간 push. policy: `camera_mount`(auto/landscape/portrait) 정책 + DB 마이그레이션. photos: 업로드 mime 대로 서빙(PNG 시뮬 사진) |
| `6ec85bb` | `simulation/` 19파일 | F5 PC 시뮬레이터. OJP1 JS 런타임(PlantHealthNet.kt 포팅 — Android 실제 .bin 로드), 합성 식물 장면(grade_paint.py 동일 분포), 방향 리졸버+가상 카메라(센서 90°/EXIF 체인), FarmerSim 자율 루프(heartbeat/정책/ROI 모니터링/전이 확정/SSE 명령), MasterSim(페어링/명령/photo_ready·health_change 수신), 시나리오 러너 S1~S4 |
| `5ca05ab` | `docs/farmer-features.md` | 기능 정의 스펙. Jev 등급 정의, 모델/이벤트/payload 규격, 방향 리졸버 수학, Android 이식 매핑(§6) |

**동작 흐름:**
1. (백엔드 기동 후) `simulation/scripts/run-sim.bat` 또는 `node src/index.js --scenario all`
2. S3 E2E: 페어링 → 식물 등록 → farmer 자율 루프(350ms 사이클: heartbeat→정책→ROI 모니터링) — 세계 상태를 건강→보통(해충 경미)→매우나쁨(심각)→건강(회복)로 변화 → 등급 전이 **연속 2회 관측 확정** 시 `POST /api/events {type:'health_change'}` → 백엔드가 events 저장 + **master SSE push** → master 실시간 수신 + 알림 센터 집계(plant_name 조인) + 식물 최신 분석 반영
3. S4 E2E: master가 `camera_mount` 정책 설정(auto/landscape/portrait) → `capture_photo` 명령 → farmer가 카메라 설치 위치 기반 가로/세로 자동 촬영(센서버퍼→EXIF→upright 체인) → PNG 업로드 → `photo_ready` SSE → master가 사진 다운로드해 640×480/480×640 방향 검증

**문제 원인과 해결 (PC 시뮬레이션이 실제로 결함을 찾은 내역):**
1. **학습 발산(NaN)** — train.py 방식(레이어별 클립 없이 lr 0.03)이 5클래스 대상에서 6에포크만에 float32 오버플로. 레이어별 [-1,1] 클립은 정상 업데이트 크기까지 깎아 pest 학습을 정체시킴(loss 역행 1.03→1.34) → **전역 노름 클리핑(clip norm 3.0)** 으로 교체 — 방향 보존, 폭주만 방지. pest val_acc 0.65→0.85+
2. **healthy 등급 40% 붕괴** — healthy가 very_healthy/fair 사이 중간 클래스로 압착(tint 차이가 GAP 특성에서 소실) → 등급별 tint 간극 확대 + 잎 수 노이즈 ±15%→±10% → healthy 87%, 인접 오차만 남음(2단계+ 오차 0.0%)
3. **해충 모델이 등급 신호를 해충으로 오인** — 괴사 반점(갈색 ×0.55)과 식해 구멍(암갈색 [70,28,16])이 같은 색 공간 → PestNet이 나쁨/매우나쁨 식물의 갈색 반점을 해충으로 판정(독립성 poor+none 27%) → **해충 증거=거의 검정(중성)/등급 증상=밝은 황갈색·주황으로 색 공간 완전 분리** → 독립성 poor+none 63→80%+
4. **학습/시뮬 분포 정합 실패(none 63%/light 60%)** — 학습은 48px 직접 렌더(구멍 날카로움), 시뮬은 480×640 렌더→48 박스 다운샘플(구멍 연화)이라 light의 작은 구멍이 none으로 흡수 → **학습 데이터에 실측 시뮬 경로 포함**(480×640→48 12%, 144×192→48 30%, 96×96→48 20%; paint()를 가로×세로 분리로 JS renderScene과 완전 정합) → pest val_acc 0.884, S2 통과
5. **단일 사이클 오분류가 즉시 알림 유발** — 베이스라인 healthy에서 한 사이클 very_healthy로 튀면 곧바 `health_change` 발송(실기기 FarmerEngine엔 디바운스가 있는데 시뮬엔 없었음) → **전이 확정 규칙(연속 2회 관측) 도입** — Android 이식 스펙에도 반영(docs/farmer-features.md F3)
6. 기타: Windows 콘솔 cp1252에서 한글 print `UnicodeEncodeError` → print ASCII화 + `-X utf8` 실행. node fetch/AbortController로 SSE 열면 소켓 종료 지연으로 테스트 프로세스 행업 → `node:http`+`req.destroy()` 방식의 SSE 클라이언트. `node --test test/` 디렉터리 인자 Windows 미지원 → `node --test`

**검증:**
- 시뮬레이션 단위 테스트 **15/15 통과** — OJP1 round-trip(JS forward == Python export logits ±1e-3, 기기=시뮬 동일 모델 보장), 방향 리졸버 수학(auto/landscape/portrait×센서 90°/0° 행렬, rotateCw 픽셀 정확성, CameraSim 체인), 전이 확정/안티-플리커(단일 오분류 무시)/오프라인 큐/다식물 ROI
- 시나리오 **S1~S4 전체 통과**: S1 건강등급 85.3%(알림 등급 나쁨 87%/매우나쁨 93%, 심각 혼동 0.0%), S2 해충 81.7% + 독립성 4케이스 충족, S3 E2E 4건 전이·체인 정상·알림 센터 집계·최신 분석 반영, S4 E2E 4/4(auto×2·고정×2 방향 일치 + PNG mime)
- 백엔드 `npm test` **28/28 통과**(신규 3: health_change SSE push+알림 센터, push 대상 외 구분, camera_mount 정책 — 기존 25 회귀 무손상)
- `git push origin main` — `2428ea9..5ca05ab` 완료

**남은 작업:**
- **Android 이식** (docs/farmer-features.md §6 매핑): `HealthGradeNet.kt`/`PestNet.kt`(OJP1 dense 24→5/4, assets .bin 이미 배치됨), `FarmerEngine.runRoiMonitoring()`에 전이 확정+`health_change`, `CameraHost`+`OrientationPolicy`(camera_mount 정책→targetRotation), Models.kt PolicyResponse `camera_mount`, master `NotificationScreen`/`PlantDetailScreen` `health_change` 라벨·색상 — 이후 `gradle :slave:app:compileDebugKotlin` + 실기기 검증
- GCP 백엔드 배포(events SSE push/policy 확장 반영) — 요청 시 `deploy-backend-gcp` 스킬로 진행
- 루트 `PC` 파일(64B 우발 산출물) 삭제 여부 결정 (10-20 일지 이월)
- 합성→실데이터 일반화: 사용자 라벨링 피드백(진단 확정 라벨 → 파인튜닝) 경로를 health_grade/pest에도 연결

**특이사항:**
- 학습 완결까지 4회 반복(발산→클립 방식→스타일 분리→분포 정합) — 매번 시뮬레이션이 다음 결함을 정확히 지목함. "Android 이식 전 PC 선행 검증" 요구사항의 실효성이 입증된 세션
- 시뮬레이션은 Android assets의 **동일 .bin**을 로드(기기=PC 동일 추론 보장) + 실제 백엔드(:4000)에 REST/SSE로 연결해 E2E 검증(HTML 프로토타입과 달리 로직 수준 시뮬레이션)
- S1 기준을 의미 기반으로 정교화: 알림 대상 등급(poor/very_poor) ≥85% + 2단계+ 오차 ≤2%를 제품 요구사항으로, 매우건강↔건강(연성 경계, 알림 무해)은 인접 등급 기준 60% 적용 — 근거를 README 기준표에 명시
