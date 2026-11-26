# model-manager — 온디바이스 모델 학습·배포·성능 관리

> 작성: 2026-11-26. 화면: `http://<서버>/model-manager` · API: `/api/models` · 코드: `backend/src/ml/`, `backend/src/routes/models.js`

Farmer 가 쓰는 OJP1 모델(검출 모듈 `health_grade`·`pest`, 식물 종 모델 14종)을 서버에서 관리한다.
학습 사진과 라벨을 모으고, 서버에서 미세조정하고, 같은 검증 사진에서 이전 버전과 비교한 뒤 배포한다.
배포하면 검출 모듈 카탈로그(`/api/detectors`, docs/farmer-features.md §8)가 새 파일을 가리키고, Farmer 가 sha256 이 바뀐 것을 보고 내려받는다.

## 1. 언제 쓰나

| 상황 | 하는 일 |
|---|---|
| **새 식물 종**이 등록됨 (예: 몬스테라) | 목록의 "모델이 없는 식물 종"에서 종 모델 만들기 → 이 종 사진 추가 → 학습 → 배포. 관수 모듈이 그 종 이름(별칭)으로 모델을 찾는다 |
| 기존 식물에 **새로 감시할 상태**가 생김 (예: 진딧물, 곰팡이) | 해당 모델에 라벨 추가 → 그 라벨 사진 3장 이상 → 학습 → 배포. 출력이 하나 늘어난 모델이 배포되고 Farmer 는 앱 업데이트 없이 새 라벨을 판독한다 |
| **판독 성능이 떨어짐** | 성능 탭의 재학습 권장 사유 확인 → 틀리는 상태의 사진을 추가(직접 업로드 / Farmer 사진 RoI 크롭 / 진단 확정 프레임) → 학습 → 이전 버전과 비교 → 배포. 나빠지면 롤백 |
| **새 검출 모듈**이 필요함 (예: 수확 시기) | 새 모델(검출 모듈)을 라벨과 함께 만들기 → 사진 → 학습 → 배포. master 식물 카드의 모듈 목록에 나타난다 |

## 2. 학습 데이터

| 출처 | 방법 | 검증 분할 묶음(group) |
|---|---|---|
| 직접 추가 | 화면에서 JPEG 여러 장 + 라벨 (휴대폰 EXIF 회전 반영) | 사진 1장 |
| Farmer 사진 | 식물을 골라 사진 선택 + 라벨 → 식물 RoI 로 잘라 저장 (RoI 없으면 전체). 다시 가져오면 라벨만 바뀜 | 같은 식물·같은 날 |
| 영상 진단 확정 | master 영상 건강진단에서 사람이 확정한 라벨의 프레임 (종 모델) | 진단 세션 |

- 저장: 긴 변 256px JPEG (`data/ml/samples/<model>/`). 학습 입력은 Farmer 와 같게 전체를 48×48 로 늘려 넣는다.
- 검증 분할: 라벨마다 묶음의 약 20% 를 검증으로. 같은 묶음은 학습/검증에 나뉘지 않는다(누출 방지). 라벨별 묶음이 4개 미만이면 검증 사진이 없다.

## 3. 학습

- 서버 worker thread 에서 순수 JS 로 OJP1 을 학습한다 (`src/ml/ojp1.js` forward/backward, `src/ml/train.js`). Python·GPU 불필요.
  forward 는 Python 학습 스크립트의 검증 벡터와, 학습 결과 파일은 Farmer(Kotlin) forward 와 일치를 테스트로 확인한다.
- 시작점: 기본은 **배포 중 버전에서 이어서**. 새 모델이거나 '기반 모델에서'를 고르면 기반 모델(종 모델은 basil, 검출 모듈은 health_grade 가 기본)의 특징 추출 층에서 시작.
- 라벨 추가: 기존 라벨 출력은 그대로 복사, 새 라벨 출력만 새로 (처음엔 거의 예측 안 되게).
- 레시피: 증강(무작위 자르기 85~100%, 좌우 반전, 밝기 ±10%, 노이즈) · SGD 모멘텀 0.9 · 에폭마다 lr ×0.9 · 전역 노름 클리핑 ·
  **L2-SP**(부모 가중치에서 멀어지는 만큼 벌점 — 사진이 적은 라벨의 기존 판독 능력 보존) · 클래스 가중치(역빈도) ·
  검증 macro-F1 이 가장 좋은 에폭을 결과로.
- 조건: 학습 사진 5장 이상, 시작 모델이 모르는 라벨은 각 3장 이상. 모델마다 동시에 작업 1개.
- 결과는 새 **버전**(배포 전 상태). 같은 검증 사진에서 시작 모델의 성능(baseline)을 함께 기록한다.

## 4. 성능 보기

| 지표 | 뜻 |
|---|---|
| 검증 정확도 / macro-F1 | 버전을 만들 때의 검증 사진 기준. 앱 내장 v1 은 **합성 이미지** 검증(ml/plant_health/metrics), 학습 버전은 **실사진** — 데이터셋이 달라 서로 직접 비교하지 않는다 |
| 이전 버전 (같은 사진) | 학습할 때 같은 검증 사진에서 잰 시작 모델 성능 — 배포 결정의 기준 |
| 라벨별 재현율 · 혼동 행렬 | 어떤 상태를 놓치고 무엇과 헷갈리는지 |
| 새 사진 정확도 | '다시 평가' 작업: 배포 버전이 학습에 보지 않은 사진(그 뒤 추가된 사진)에서의 정확도 — 현장 데이터에 대한 가장 정직한 지표 |
| 현장 평균 신뢰도 · 60% 미만 비율 | Farmer 가 보낸 판독 결과(`detector_results`)의 신뢰도 — 일별 추이, 버전(sha)별 |
| 사람 확인 일치율 | 영상 진단에서 사람이 확정한 라벨과 AI 라벨 일치 (종 모델, 90일) |

**재학습 권장** (경고): 현장 평균 신뢰도가 직전 7일보다 10%p 이상 하락 / 60% 미만 판독이 30% 초과 (각 20건 이상) /
새 사진 정확도 70% 미만 (10장 이상) / 사람 확인 일치율 70% 미만 (5건 이상).
**학습 가능** (안내): 학습에 안 쓴 새 사진 10장 이상 / 아직 학습 안 된 라벨이 있음.

종 모델의 현장 결과는 관수 모듈이 그 종 모델로 판독한 결과(`detail.source = species:<id>`)이고, 신뢰도는 물 부족/적정/과다 판정의 신뢰도다.

## 5. 배포 · 롤백

- 버전 '배포' → `ml_models.active_version` 변경 → `/api/detectors` 카탈로그의 모델 url·sha256·라벨이 바뀜 →
  식물이 있는 모든 Farmer 에 `detectors_sync` 명령 → Farmer 가 새 파일을 받아 검증 후 사용 (5분 주기 동기화로도 반영).
- 롤백 = 이전 버전 배포. 버전 파일은 지우지 않는다(`data/ml/models/<model>/<model>-v<N>.bin`, 내장 v1 은 `backend/models`).
- 라벨을 늘린 버전은 Farmer 앱 2026112604 이후 빌드가 필요하다(버전별 출력 라벨 사용). 이전 빌드는 출력 수가 맞지 않으면 앱 내장 모델로 돌아간다.

## 6. 운영

- 접근 제한: 환경변수 `MODEL_MANAGER_TOKEN` 을 두면 모든 `/api/models` 요청에 `x-admin-token` 헤더(화면은 처음에 입력, 이미지 링크는 `?token=`)가 필요하다. 없으면 열려 있다(현재 `/admin` 과 같음).
- 저장 위치: `ML_DATA_DIR`(기본 `backend/data/ml`) — 사진·학습 버전 파일. DB 테이블 `ml_models`, `ml_model_versions`, `ml_samples`, `ml_jobs`.
- 학습 시간: 사진 수 × 에폭에 비례 (이 PC 기준 사진 1장·에폭당 약 15ms — 300장 × 15에폭 ≈ 1분). 서버 재시작 시 진행 중 작업은 실패로 표시된다.
- 앱 내장 모델을 Python 으로 다시 학습했다면 `node scripts/sync-detector-models.mjs` 로 `backend/models` 를 맞춘다 (내장 v1 의 sha 가 갱신됨).
- 학습 코드를 바꿨다면 `node scripts/make-ojp1-parity-fixture.mjs` 로 Farmer 패리티 fixture 를 다시 만든다.

## 7. API 요약

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/models` | 모델 목록(성능 요약·상태) + 모델 없는 식물 종 + 진행 중 작업 |
| POST | `/api/models` | 새 모델 `{id, kind: species|detector, name, nameEn, icon, aliases[], labels[], baseModel}` |
| GET / PUT / DELETE | `/api/models/:id` | 상세(버전·지표·사진 수·현장 성능·상태) / 이름·별칭 수정 / 삭제(앱 내장 모델 제외) |
| POST | `/api/models/:id/labels` | 라벨 추가 `{id, ko, en}` |
| GET / POST | `/api/models/:id/samples` | 사진 목록 / 업로드(multipart `photos`, `label`, `note`) |
| PUT / DELETE | `/api/models/:id/samples/:sid` | 라벨 수정 / 삭제 |
| GET | `/api/models/:id/candidates?plantId=` | 가져올 수 있는 식물과 Farmer 사진 |
| POST | `/api/models/:id/samples/import` | Farmer 사진 가져오기 `{photos:[{photoId,label}]}` |
| POST | `/api/models/:id/samples/import-diagnosis` | 영상 진단 확정 프레임 가져오기 (종 모델) |
| POST | `/api/models/:id/train` | 학습 `{epochs, lr, from: active|base}` → `{jobId}` |
| POST | `/api/models/:id/evaluate` | 배포 버전 다시 평가 → `{jobId}` |
| GET | `/api/models/jobs/:jobId` | 작업 진행률·결과 |
| POST | `/api/models/:id/versions/:v/activate` | 배포/롤백 |
| GET | `/api/models/:id/versions/:v/download` | 모델 파일 |

## 8. 한계

- 앱 내장 모델은 합성 이미지로 학습됐다. 실사진이 쌓이기 전까지 내장 v1 의 검증 정확도는 실제 현장 성능을 보장하지 않는다 — 새 사진 정확도와 현장 신뢰도를 함께 볼 것.
- 신뢰도는 정답이 아니다. 현장 신뢰도 하락은 '의심 신호'이고, 확정은 사람이 라벨을 붙인 사진(새 사진 정확도·사람 확인 일치율)으로 한다.
- 모델 구조(conv8-16-24 + GAP + dense, 48×48)는 고정. 더 큰 모델이 필요하면 Farmer 런타임부터 바꿔야 한다.
