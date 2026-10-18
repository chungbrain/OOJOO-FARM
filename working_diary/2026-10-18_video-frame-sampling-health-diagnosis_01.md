# 작업 일지 — 2026-10-18

## 영상 프레임 샘플링 건강진단 파이프라인 — 바질 선정 + 사람 라벨 확정 흐름

**요구사항:**
1. Farmer 앱에서 영상을 보고 식물의 건강상태를 판별하는 기능 개발
2. 건강 판별이 쉬운 식물 1종 선정
3. 영상 프레임 샘플링 판별 파이프라인 설계·구현
4. 영상 판별 라벨은 사람의 개입으로 확정 (human-in-the-loop)
5. 기존 코드(capture3s, analyzeRoi, PlantHealthNet, uploadVideo 등) 패턴 최대한 참조

**선정 근거 (바질):**
- 종별 CNN val_acc: 딸기 98.1% / 바질 94.4% / 호박·애호박 92.6% / 고추 83.3% / 방울토마토 82.4% / 허브 76.9% / 선인장 61.1%
- 웹(CC 사진) 일반화: 바질 36.4%로 고정확 모델 중 최상 (딸기는 light_low 쏠림 과적합, 선인장은 정확도 최하)
- 재배 용이성 + 수분 스트레스 시들음이 뚜렷 → 사람 라벨 기준 명확

**변경 내용 (3 커밋):**

| 커밋 | 파일 | 내용 |
|---|---|---|
| `819315c` | `backend/src/routes/diagnosis.js` 신규 | 진단 세션 API: 업로드(slaveAuth+multer, 프레임 최대 8장), 식물별 조회(최신순 20), 라벨 확정(6클래스 검증), 프레임 이미지 서빙(경로탐색 방지). `diagnosis_sessions`/`diagnosis_frames` 테이블. SSE `diagnosis_ready` 이벤트 |
| | `backend/test/diagnosis.test.js` 신규 | 실서버 E2E 2건: 인증→multipart 업로드→조회→확정 / 400·401·404 검증 실패 케이스 |
| | `backend/src/app.js` | `/api/diagnosis` 라우트 마운트 |
| | `.gitignore` | `backend/data/diagnosis/*` 추가 (photos/* 패턴 준수) |
| `f5d6f78` | `slave/vision/VideoFrameSampler.kt` 신규 | MediaExtractor+MediaCodec MP4 디코딩, 500ms 간격 최대 6프레임 샘플. stride 기반 수동 YUV→Bitmap (minSdk 24 — `Image.getBitmap()` API 26 사용 불가), 640px 상한, 15s 안전 타임아웃 |
| | `slave/service/FarmerEngine.kt` | `diagnose_video` 명령 (SSE+폴링 양쪽): capture3s → 샘플링 → 프레임별 CNN 진단(ROI 크롭 지원, 대상 식물 모델 직접 지정 — 전역 speciesId 오염 방지) → 다수결 집계(동률 시 평균 신뢰도) → 영상(kind=diagnosis)+세션 업로드 |
| | `slave/model/Models.kt`, `network/ApiService.kt` | `uploadDiagnosisSession` multipart API |
| `9c905a5` | `master/ui/DiagnosisReviewScreen.kt` 신규 | 세션 리뷰 화면: AI 판정 칩+모델 배지, 영상 재생, 2×3 프레임 갤러리(프레임별 판정 캡션), 6클래스 라벨 확정 UI(AI 추천 표시), 3초 폴링(90s 상한, 신규 세션 도착 시 조기 종료) |
| | `master/ui/PlantDetailScreen.kt` | "영상 건강진단" 버튼 → `diagnose_video` 명령 → 리뷰 화면 진입 |
| | `master/MainActivity.kt` | `diagnosis/{plantId}` 라우트 등록 |
| | `master/model/Models.kt`, `network/ApiService.kt`, `data/AppLocale.kt`, `ui/LocalizedMessages.kt`, `values{,-en}/strings.xml` | 진단 모델·API·문자열 23개 ko/en |

**동작 흐름:**
1. Master 식물 상세 → "영상 건강진단" → `POST /api/commands {action: diagnose_video}` → SSE → Slave
2. Slave: 카메라 준비 → 3초 MP4 캡처 → 6프레임 샘플링 → ROI(있으면) 크롭 후 `PlantHealthNet.diagnoseWithModel("basil", …)` → 다수결 AI 라벨
3. 영상 업로드(kind=diagnosis) → 진단 세션 업로드(프레임 JPEG 6장 + framesMeta) → SSE `diagnosis_ready`
4. Master 리뷰 화면: 영상+프레임 확인 → 사람이 6클래스(건강/물 부족/물 과다/햇빛 부족/해충 발생/온도 더움) 중 확정 → `POST /sessions/:id/confirm`
5. 확정 라벨은 `diagnosis_sessions.human_label`에 저장 — 향후 모델 개인화 학습 데이터(Phase 6 연계)

**검증:**
- 백엔드 `npm test` — **25/25 통과** (진단 E2E 2건 포함, 임시 DB 격리)
- `:slave:app:compileDebugKotlin` — **BUILD SUCCESSFUL**
- `:master:app:compileDebugKotlin` — **BUILD SUCCESSFUL**
- `node scripts/verify-locales.mjs` — **2모듈 패리티 통과**
- `git push origin main` — `597fe05..9c905a5` 완료

**남은 작업:**
- 실기기/에뮬레이터 E2E: 실 카메라 영상으로 MediaCodec 디코딩·YUV 변환·프레임 품질 확인 (컴파일 검증만으로는 미확인)
- GCP 서버 배포 (`deploy-backend-gcp` 스킬) — 현재 GCP는 구 코드
- APK 빌드 (`build-apk-dist` 스킬)
- 확정 라벨 → `ml/plant_health` 학습 세트 추출 스크립트 (모델 개인화)
- 루트 `PC` 파일(8/6일자 스크립트 실수 산출물, 64B) 삭제 여부 결정 필요

**특이사항:**
- 구현 병렬 위임 중 Farmer 태스크가 gradle lock 경합으로 타임아웃 → 코드는 완성 상태로 남아 직접 코드 리뷰+컴파일 검증으로 마무리
- 기존 코드에 MP4 디코딩 경로가 없어(인코딩만 존재) `GrowthClipRenderer`의 MediaCodec 관용구를 디코더에 적용, YUV 처리는 `PlantHealthNet.yuvToRgb48`/`PlantAnalyzer.analyzeYUV` 스트라이드 패턴 재사용
