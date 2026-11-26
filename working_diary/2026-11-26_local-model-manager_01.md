# 2026-11-26 model-manager 로컬로 이전 — 로컬 학습, 서버·GitHub 업로드 (local-model-manager)

## 요구사항

- model-manager 는 **로컬**(이 PC)에 있어야 한다.
- 생성된 모델과 학습·검증 데이터, 라벨을 **서버**와 **GitHub** 에 업로드한다.
- (직전 요청) GCP 서버 재배포.

## 바뀐 구조

직전 커밋(`0f8ad93`)은 학습·라벨링·화면까지 서버(`/model-manager`)에 있었다. 이를 나눴다.

| 위치 | 역할 |
|---|---|
| 로컬 `ml/model-manager/` | 화면(127.0.0.1:4900), 학습 사진·라벨 관리, 학습·평가(worker), 성능 보기, 서버 업로드·배포, GitHub 커밋·push |
| 로컬 `ml/registry/` | 원본 데이터 — 모델 정의·라벨, 학습 사진(sha256 이름)·분할, 버전(model.bin·지표·학습에 쓴 사진 목록). 그대로 GitHub 에 올라감 |
| 서버 `/api/models` | 릴리스 받기(모델+라벨+지표+사진, 토큰), 배포/롤백(→ farmer 카탈로그), 현장 성능·사람 일치율·farmer 사진 후보·진단 프레임 조회 |

- 서버에서 지운 것: `/model-manager` 화면, 학습/평가 작업, 사진 CRUD, `jpeg-js` 의존성. 학습 엔진·화면은 `git mv` 로 로컬 도구로 옮김.
- 공유: `backend/src/ml/ojp1.js`(모델 연산), `backend/src/ml/defs.js`(내장 모델 정의) — 로컬 도구가 import.
- 서버 쓰기는 `MODEL_MANAGER_TOKEN` 필수(설정이 없으면 업로드 거부). 로컬 설정(`.local/config.json`, 커밋 안 됨)에 같은 토큰.
- 로컬 서버는 127.0.0.1 에만 열고, Host 검사 + 쓰기 요청에 `x-mm-local` 헤더를 요구(다른 사이트의 요청 위조·DNS rebinding 차단).
- GitHub 올리기는 `ml/registry/<모델>` 만 `git commit -- <경로>` 로 커밋 → 작업 중인 다른 파일은 커밋되지 않음.
- 업로드는 서버에 없는 사진만 보냄(`datasets/missing`), 같은 파일 재업로드는 `exists`, 같은 버전 번호에 다른 파일은 409(여러 PC → git pull 로 맞춤).

## 변경 파일

| 파일 | 내용 |
|---|---|
| `ml/model-manager/server.mjs` (신규) | 로컬 API·화면, 로컬 재학습 판정 |
| `ml/model-manager/lib/store.js` (신규) | 파일 레지스트리 + 앱 내장 16개 v1 시드 |
| `ml/model-manager/lib/remote.js`, `git.js` (신규) | 서버 연결·업로드 / GitHub 커밋·push |
| `ml/model-manager/lib/{train,image,worker,jobs}.js` | 서버에서 이동 (jobs 는 파일 레지스트리용으로 재작성) |
| `ml/model-manager/ui/index.html` | 서버 화면에서 이동 + 서버 상태·설정·업로드/배포·GitHub |
| `ml/model-manager/test/{engine,local}.test.js` | 엔진 테스트 이동 + 로컬 E2E (실제 백엔드 + 임시 git 저장소/bare 원격) |
| `ml/registry/` (신규) | 앱 내장 16개 모델 v1 (model.json · samples.json · versions/v1/version.json) |
| `backend/src/routes/models.js` | 업로드·배포·조회 API 로 재작성 |
| `backend/src/ml/registry.js`, `stats.js`, `defs.js`(신규) | 배포 레지스트리(데이터셋 기록 포함), 현장 성능만, 공유 정의 |
| `backend/test/model_releases.test.js` | 릴리스 API 테스트로 재작성 |
| `scripts/model-manager.bat` (신규) | Windows 실행기 |
| `docs/model-manager.md` | 로컬 구조로 다시 씀 |

## 검증 결과

- 로컬 `npm test`: 엔진 7 + 로컬 E2E 6 — 로컬 학습 → 레지스트리 파일 → 서버 업로드(사진 36장)·배포 → farmer 카탈로그 반영, 재업로드 시 사진 0장 전송, 평가(새 사진만 unseen),
  farmer 사진 서버에서 받아 RoI 크롭, 새 종 모델 업로드 후 관수 모듈 별칭 연결, GitHub push(모델 폴더만, 다른 파일 제외), 로컬 전용 가드
- 백엔드 `npm test` 131/132 — 실패 1건은 기존과 같은 Windows SIGTERM 테스트. 릴리스 API 6/6
- 패리티 fixture 재생성 결과 바이트 동일 (학습 결정적)
- 화면: 임시 백엔드 + 로컬 도구(임시 레지스트리/저장소)를 헤드리스 Edge 로 캡처 — 목록(로컬 최신/서버 배포/GitHub 상태), 학습·업로드·배포(버전별 서버 상태·업로드/배포/롤백 버튼, GitHub 패널), 성능(현장 추이는 서버에서)
- 앱(farmer/master) 코드 변경 없음 → APK 재빌드 없음

## 남은 작업

- GCP 재배포 — 이 PC 에 gcloud 설치(594.0.1)는 했고 로그인(`gcloud auth login`) 대기 중. 배포 때 서버 `MODEL_MANAGER_TOKEN` 설정 + 같은 값을 로컬 설정에 저장
- 실사진으로 내장 모델 '다시 평가'
