# model-manager — 로컬에서 학습하고, 서버와 GitHub 에 올린다

> 작성: 2026-11-26. 실행: `scripts\model-manager.bat` (또는 `cd ml/model-manager && npm install && npm start`) → http://127.0.0.1:4900
> 코드: `ml/model-manager/` (로컬 도구) · `backend/src/routes/models.js` (서버 업로드·배포 API) · 데이터: `ml/registry/`

Farmer 가 쓰는 OJP1 모델(검출 모듈 `health_grade`·`pest`, 식물 종 모델 14종, 새로 만든 모델)을
**이 PC 에서** 관리한다. 학습 사진·라벨 관리, 학습, 평가, 성능 보기는 모두 로컬에서 하고,
결과(모델 · 라벨 · 학습/검증 사진 · 지표)를 **서버**(Farmer 배포)와 **GitHub**(저장소 기록)에 올린다.

```
          ┌──────────────── 이 PC ────────────────┐
 사진 추가 │ model-manager (127.0.0.1:4900)        │  업로드·배포   ┌──── 서버 (GCP) ────┐   다운로드
 라벨 ────▶│  학습·평가 (worker thread)            │──────────────▶│ /api/models        │──────────▶ Farmer
          │  ml/registry/  ← 원본 (파일)          │◀──────────────│ 현장 성능·farmer 사진│ (sha256 검증)
          └────────────┬──────────────────────────┘   조회         └────────────────────┘
                       │ git commit + push (ml/registry/<모델>)
                       ▼
                    GitHub
```

## 1. 언제 쓰나

| 상황 | 하는 일 |
|---|---|
| **새 식물 종**이 등록됨 (예: 몬스테라) | 목록의 "모델이 없는 식물 종"(서버 식물 기준)에서 종 모델 만들기 → 사진 → 학습 → 서버 업로드+배포 → GitHub |
| 기존 식물에 **새로 감시할 상태** (예: 진딧물) | 라벨 추가 → 그 라벨 사진 3장 이상 → 학습 → 업로드+배포. Farmer 는 앱 업데이트 없이 새 라벨을 판독 |
| **판독 성능 저하** | 성능 탭의 재학습 권장 사유 확인 → 틀리는 상태의 사진 추가(직접 / Farmer 사진 RoI 크롭 / 진단 확정 프레임) → 학습 → 시작 버전과 비교 → 업로드+배포. 나빠지면 롤백 |
| **새 검출 모듈** (예: 수확 시기) | 새 모델(검출 모듈)을 라벨과 함께 만들기 → 사진 → 학습 → 업로드+배포 → master 식물 카드 모듈 목록에 나타남 |

## 2. 로컬 레지스트리 `ml/registry/` (GitHub 에 그대로 올라가는 원본)

```
ml/registry/<model>/model.json                  종류·이름·라벨(ko/en)·종 별칭·기반 모델
ml/registry/<model>/samples.json                학습 사진 목록: 라벨·학습/검증 분할·출처·묶음·순번(seq)
ml/registry/<model>/samples/<sha256>.jpg        학습 사진 (긴 변 256px)
ml/registry/<model>/versions/v<N>/version.json  출력 라벨·sha256·검증 지표·시작 버전 비교·학습 설정·학습에 쓴 사진 목록·평가
ml/registry/<model>/versions/v<N>/model.bin     학습한 모델 (v1 = 앱 내장 → backend/models/<id>.bin 을 가리킴)
```

- 처음 실행하면 앱 내장 16개 모델이 v1 로 생긴다 (합성 데이터 검증 지표 포함). 새 모델·학습 버전은 v2 부터.
- 여러 PC 에서 쓰면 `git pull` 로 레지스트리를 맞춘 뒤 작업한다 — 서버는 같은 버전 번호에 다른 파일이 오면 거부한다(409).
- 설정(서버 주소·업로드 토큰·커밋 이름)은 `ml/model-manager/.local/config.json` — 커밋되지 않는다.

## 3. 학습 데이터

| 출처 | 방법 | 검증 분할 묶음 |
|---|---|---|
| 직접 추가 | JPEG 여러 장 + 라벨 (휴대폰 EXIF 회전 반영, 같은 사진은 한 번만) | 사진 1장 |
| Farmer 사진 | 서버의 식물·사진 목록에서 골라 라벨 → 원본을 받아 **식물 RoI 로 잘라** 저장 (RoI 없으면 전체). 다시 가져오면 라벨만 바뀜 | 같은 식물·같은 날 |
| 영상 진단 확정 | 서버의 진단 세션 중 사람이 확정한 라벨의 프레임 (종 모델) | 진단 세션 |

라벨마다 묶음의 약 20% 가 검증용. 같은 묶음은 학습/검증에 나뉘지 않는다. 이미 어떤 버전 학습에 쓰인 사진은 목록에서 빼도 파일은 기록으로 남는다.

## 4. 학습 (이 PC)

- worker thread 에서 순수 JS 로 OJP1 학습 (`ml/model-manager/lib/train.js`, 연산은 `backend/src/ml/ojp1.js`).
  forward = Python 학습 검증 벡터, backward = 수치 미분, 학습 결과 = Farmer(Kotlin) forward 와 일치를 테스트로 확인.
- 시작점: 버전을 골라 이어서(기본 = 서버 배포 버전), 또는 기반 모델(종 모델 basil / 검출 모듈 health_grade 기본)에서.
- 라벨 추가: 기존 출력은 그대로, 새 라벨 출력만 새로.
- 레시피: 증강(자르기 85~100%·반전·밝기·노이즈) · SGD 모멘텀 · lr 감쇠 · 노름 클리핑 · **L2-SP**(시작 가중치에서 멀어지면 벌점 — 사진이 적은 라벨의 기존 능력 보존) · 클래스 가중치 · 검증 macro-F1 최고 에폭.
- 조건: 학습 사진 5장 이상, 시작 모델이 모르는 라벨은 각 3장 이상.
- 결과 = 새 버전 폴더. 같은 검증 사진에서 시작 버전 성능(baseline)을 함께 기록한다.

## 5. 성능 보기

| 지표 | 어디서 | 뜻 |
|---|---|---|
| 검증 정확도 / macro-F1 · 라벨별 재현율 · 혼동 행렬 | 로컬 | 버전 학습 때의 검증 사진 기준. 내장 v1 은 **합성 이미지**, 학습 버전은 **실사진** — 직접 비교하지 않는다 |
| 시작 버전 (같은 사진) | 로컬 | 같은 검증 사진에서 잰 시작 버전 성능 — 업로드·배포 판단 기준 |
| 새 사진 정확도 | 로컬 | '다시 평가': 그 버전이 학습에 보지 않은(뒤에 추가된) 사진에서의 정확도 |
| 현장 평균 신뢰도 · 60% 미만 비율 (일별, 버전별) | 서버 | Farmer 판독 결과(`detector_results`) |
| 사람 확인 일치율 | 서버 | 영상 진단 사람 확정 vs AI (종 모델, 90일) |

**재학습 권장**(경고): 현장 평균 신뢰도 직전 7일보다 10%p 이상 하락 / 60% 미만 30% 초과 (각 20건 이상) / 새 사진 정확도 70% 미만 (10장 이상) / 사람 확인 일치율 70% 미만 (5건 이상).
**안내**: 학습에 안 쓴 새 사진 10장 이상 / 학습 안 된 라벨 / 로컬 버전이 서버 배포 버전보다 새로움.

## 6. 서버에 올리기 · 배포

- **서버 업로드**: 버전의 모델 파일 + 모델 정의(라벨 ko/en·별칭) + 지표 + 그 버전의 학습·검증 사진(서버에 없는 것만, sha256 이름)을 `POST /api/models/:id/releases` 로.
  서버는 OJP1 구조·출력 수·sha256·사진 sha 를 검증하고 `data/ml/models`, `data/ml/datasets` 에 보관한다. 같은 파일 재업로드는 `exists`.
- **배포**(업로드+배포 / 배포 / 롤백): `POST /api/models/:id/versions/:v/activate` → `/api/detectors` 카탈로그의 url·sha256·라벨이 바뀌고,
  식물이 있는 모든 Farmer 에 `detectors_sync` → Farmer 가 새 파일을 받아 sha256 검증 후 사용.
- 라벨을 늘린 버전은 Farmer 앱 2026112604 이후 빌드에서 쓰인다 (이전 빌드는 내장 모델로 돌아감).
- 서버 쓰기는 **`MODEL_MANAGER_TOKEN`** 필수 — 서버에 설정이 없으면 업로드·배포가 거부된다. 같은 값을 로컬 설정의 업로드 토큰에 넣는다.

## 7. GitHub 에 올리기

- 모델 화면(또는 목록의 "GitHub 에 올리기")에서 `ml/registry/<모델>/` **만** 커밋하고 현재 브랜치를 push 한다 — 다른 작업 중인 파일은 커밋하지 않는다.
- 커밋 이름/이메일은 설정값, 없으면 git 설정. push 가 거부되면(원격이 앞섬) `git pull` 후 다시.
- 사진은 256px JPEG 라 장당 10~30KB. 수천 장이 되면 저장소 크기를 보고 Git LFS 를 검토한다.

## 8. 서버 API (`/api/models`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/models` | 모델 목록(배포 버전·현장 성능 요약·사용 식물) + 모델 없는 식물 종 |
| GET | `/api/models/:id` | 버전(업로드·배포 상태)·현장 성능·사람 일치율·재학습 신호 |
| GET | `/api/models/:id/candidates?plantId=&kind=&aliases=` | 식물(RoI 포함)·farmer 사진 |
| GET | `/api/models/:id/diagnosis-frames` | 진단 확정 프레임 |
| POST | `/api/models/:id/datasets/missing` | 서버에 없는 사진 sha256 `{shas}` [토큰] |
| POST | `/api/models/:id/releases` | 업로드 multipart `manifest`, `model`, `samples` [토큰] |
| POST | `/api/models/:id/versions/:v/activate` | 배포/롤백 [토큰] |
| GET | `/api/models/:id/versions/:v/download` · `/dataset` | 모델 파일 · 학습/검증 사진 목록 |

## 9. 운영 메모

- 앱 내장 모델을 Python(ml/plant_health)으로 다시 학습했다면 `node scripts/sync-detector-models.mjs` → 로컬 실행 시 v1 sha 갱신.
- 학습 코드를 바꿨다면 `node scripts/make-ojp1-parity-fixture.mjs` 로 Farmer 패리티 fixture 재생성.
- 테스트: `cd ml/model-manager && npm test` (학습 엔진 + 실제 백엔드로 업로드·배포 + 임시 저장소로 GitHub push), `cd backend && npm test`.

## 10. 한계

- 내장 모델은 합성 이미지로 학습됐다 — 실사진 기준 성능은 Farmer 사진에 라벨을 붙여 '다시 평가'해야 처음 나온다.
- 현장 신뢰도 하락은 의심 신호일 뿐, 확정은 사람이 라벨을 붙인 사진으로 한다.
- 모델 구조(conv8-16-24 + GAP + dense, 48×48)는 고정. 더 큰 모델은 Farmer 런타임부터 바꿔야 한다.
