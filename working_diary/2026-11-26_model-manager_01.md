# 2026-11-26 model-manager — 학습 사진·라벨 관리, 서버 학습, 버전 배포, 모델 성능 (model-manager)

## 요구사항

1. 새 식물이 추가되거나, 기존 식물에 모니터링할 새 사진·라벨이 생기면 학습할 수 있어야 한다.
2. 판독(모니터링) 성능이 떨어지면, 그 상태의 기존 식물-라벨 사진을 인위적으로 추가해 다시 학습할 수 있어야 한다.
3. model-manager 에서 각 모델의 성능을 볼 수 있어야 한다.

## 기존 상태

- 모델 학습은 `ml/plant_health` Python(numpy) 스크립트로 개발 PC 에서만 가능. 서버에는 학습·버전 개념이 없음.
- 진단 확정 라벨로 basil 을 미세조정하는 파이프라인(2026-10-20)은 있었지만 수동 실행·종 1개.
- 앱 내장 모델은 합성 이미지 학습 — 실사진 성능은 따로 측정할 방법이 없었음.
- 직전 작업(검출 모듈)으로 서버가 모델 파일을 배포하고 Farmer 가 sha256 검증 후 받는 경로는 있었음 → 배포는 이 경로를 재사용.

## 설계 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 학습 위치 | 백엔드 worker thread, 순수 JS 로 OJP1 학습 | GCP VM 에 Python/numpy 없이 `npm install` 만으로 동작. 모델이 작아(파라미터 ~5천) CPU 로 충분 |
| 정합성 | JS forward = Python 검증 벡터, JS backward = 수치 미분, JS 학습 결과 = Kotlin forward | 세 런타임이 같은 파일을 같게 계산해야 배포가 의미 있음 |
| 학습 방식 | 배포 버전(또는 기반 모델)에서 미세조정 + L2-SP | 사진이 적은 라벨에서 기존 판독 능력을 잃지 않게 (합성 데이터 리플레이를 대신) |
| 라벨 추가 | 기존 출력 열 복사 + 새 열만 새로 | 기존 라벨 출력이 바뀌지 않음 (테스트로 확인) |
| 배포 | 레지스트리 active 버전이 검출 카탈로그를 결정 → `detectors_sync` | Farmer 수정 최소, 롤백 = 이전 버전 배포 |
| 검증 분할 | 사진 묶음(같은 사진/같은 날 같은 식물/같은 진단 세션) 단위 약 20% | 비슷한 사진이 학습·검증에 나뉘는 누출 방지 |
| 성능 | 검증(합성/실사진 구분) + 이전 버전 동일 검증셋 비교 + 새 사진 정확도 + 현장 신뢰도(버전별) + 사람 일치율 | 합성 검증만으론 현장 성능을 알 수 없음 |

## 변경 내역

| 파일 | 내용 |
|---|---|
| `backend/src/ml/ojp1.js` (신규) | OJP1 파싱/직렬화/forward/backward, adaptModel, seed RNG |
| `backend/src/ml/train.js` (신규) | 증강·미세조정·평가 지표 |
| `backend/src/ml/image.js` (신규) | JPEG 디코드(EXIF 회전)·RoI 크롭·면적 평균 축소·인코드 (`jpeg-js` 의존성 추가) |
| `backend/src/ml/registry.js` (신규) | 모델·버전·사진·작업 테이블, 내장 16개 모델 v1 시드(합성 검증 지표 포함), 종 별칭 |
| `backend/src/ml/jobs.js`, `worker.js` (신규) | 작업 큐(1개씩), worker 학습/평가, 버전 기록 |
| `backend/src/ml/stats.js` (신규) | 현장 성능·사람 일치율·사용 식물·모델 없는 종·재학습 권장 판정 |
| `backend/src/routes/models.js` (신규) | `/api/models` — 모델/라벨/사진/가져오기/학습/평가/배포/다운로드, 선택적 토큰 |
| `backend/src/model-manager.html` (신규) | `/model-manager` 화면 — 목록, 성능·학습 데이터·학습·배포 탭 |
| `backend/src/lib/detectors.js` | 카탈로그를 레지스트리 배포 버전으로 — 버전별 라벨, `speciesAliases`, 사용자 검출 모듈 |
| `backend/src/routes/detectors.js`, `plants.js` | 모듈 id 동적 목록, 식물 목록당 카탈로그 1회 계산 |
| `android/slave/.../detect/DetectorManager.kt`, `model/Models.kt`, `DetectorLogic.kt` | `ModelRef.labels`(버전별 출력 순서), `speciesAliases` 로 새 종 찾기 |
| 테스트 | `backend/test/ml_engine.test.js`(7), `model_manager.test.js`(7), farmer `TrainedModelParityTest`(2), 계약 fixture 재생성 |
| `scripts/make-ojp1-parity-fixture.mjs` (신규) | JS 학습 모델 → Kotlin 패리티 fixture |
| `docs/model-manager.md` (신규), `docs/farmer-features.md` | 사용·운영 문서 |

## 검증 결과

- `ml_engine.test.js` ✅ 7/7 — forward 가 Python 벡터(건강등급·해충·종 14)와 1e-3 이내 일치, 직렬화 바이트 동일, 기울기 = 수치 미분(90% 이상 일치), 라벨 추가 시 기존 출력 동일, 합성 3라벨 학습 검증 정확도 ≥ 90%, EXIF 회전·RoI 크롭
- `model_manager.test.js` ✅ 7/7 — 사진 업로드(비 JPEG 거부) → 라벨 추가(3장 미만 학습 거부) → 학습 v2(worker) → 같은 검증셋 이전 버전 비교 → 배포 시 `/api/detectors` 라벨 5개·새 파일·sha 일치·farmer 동기화 명령 → 새 라벨 결과 수용 → 롤백 → 평가(배포 뒤 사진만 '새 사진'). farmer 사진 RoI 크롭 가져오기/재라벨. 새 종(몬스테라) 생성→학습→배포 후 관수 모듈 별칭 연결. 새 검출 모듈 생성→배포→master 식물 카드 추가→삭제. 저신뢰 현장 판독 → 재학습 권장. 토큰
- 백엔드 전체 `npm test` 139/140 — 실패 1건은 기존과 같은 Windows SIGTERM 테스트
- farmer `:slave:app:testDebugUnitTest` ✅ 31/31 (TrainedModelParityTest 2 신규 — 서버 JS 학습 모델의 Kotlin forward 일치), master ✅ 19/19
- 화면: 데모 데이터(해충 모델 v2 학습, 21일치 현장 판독)를 넣은 로컬 서버를 헤드리스 Edge 로 캡처해 확인 — 목록/성능/학습 데이터/학습·배포, 다크 모드, 390px 폭.
  고친 것: 차트를 viewBox 확대 대신 실제 폭으로 그림(글자 과대·끝 라벨 넘침), 2단 그리드 최소폭 0(좁은 화면 넘침), 라벨 id → 이름
- 색: dataviz 팔레트 슬롯 1·2 `validate_palette.js` 라이트/다크 통과. 라이트 주황 대비 2.88:1 → 값 직접 표기 + 표로 보기 제공
- `./scripts/build-dist.sh` ✅ → farmer 18,562,782 bytes (master 코드 변경 없어 기존 APK 유지)
- **실기기 미확인** — Farmer 가 실제로 새 버전을 받아 판독하는 흐름은 서버 계약·Kotlin 단위 테스트까지만 확인

## 배포 주의

- GCP 재배포 필요: `git pull` → **`npm install`**(jpeg-js 추가) → `oojoo.service` 재시작. 첫 기동 때 내장 모델 16개가 v1 으로 등록됨.
- 운영 서버에는 `MODEL_MANAGER_TOKEN` 설정 권장 (없으면 `/model-manager` 가 누구에게나 열려 있음 — 배포 권한 포함).
- 라벨을 늘린 버전은 farmer 2026112604 이후 빌드에서 쓰인다. 이전 빌드는 출력 수 불일치로 내장 모델로 돌아감.
- farmer APK 서명은 2026112603 과 같음 (업데이트 설치 가능).

## 커밋

- `0f8ad93 feat(backend): model-manager — label/photo management, server-side OJP1 training, versioned deploy, model performance`
- `4f69d0f feat(farmer): use per-version model labels and server species aliases from the catalog`
- `f22059f build: debug APKs 2026112604 with model-manager model labels and species aliases`

## 남은 작업

- 실사진 확보: 실제 Farmer 사진에 라벨을 붙여 내장 모델(합성 학습)의 새 사진 정확도부터 측정
- GCP 재배포 + 토큰 설정
- master 앱에서 판독 결과에 "틀렸어요"(정답 라벨) 피드백 → 학습 사진으로 바로 쌓는 경로 (현재는 model-manager 에서 Farmer 사진을 골라 라벨링)
- 학습 작업이 오래 걸리면 진행 중 취소 버튼
