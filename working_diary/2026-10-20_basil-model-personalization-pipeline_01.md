# 작업 일지 — 2026-10-20

## 바질 모델 개인화 파이프라인 (확정 라벨 → 학습 → 재배포) + GCP 배포

**요구사항:**
1. Farmer 앱의 부족한 개발 부분 보완 — 식물 1종(바질) 우선
2. 식물 건강상태를 정확하게 판단하는 것이 목표
3. GitHub 커밋·푸시 + GCP 백엔드 배포

**파악된 갭 (2026-10-18 일지의 남은 작업과 직결):**
- 진단 플로우(촬영→샘플링→CNN→사람 라벨 확정)는 완성돼 있으나, 확정 라벨(`diagnosis_sessions.human_label`)이 모델 학습으로 되돌아가는 경로가 전혀 없었음
- 바질 모델은 합성 이미지 학습(val_acc 94.4%)이라 실 카메라 화면 일반화가 약함(웹 평가 36.4%) — 실기기 프레임 + 사람 라벨이 유일하게 배포 환경에 가까운 학습 데이터인데 버려지고 있었음

**변경 내용 (커밋 `c885cd8` — 3 파일 신규, 기존 파일 무변경):**

| 파일 | 내용 |
|---|---|
| `ml/plant_health/export_diagnosis.py` 신규 | 백엔드 DB에서 확정 라벨 세션 조회(읽기 전용 URI) → 대상 종 프레임 JPEG를 `diagnosis_data/`로 추출. 종 해석은 `PlantHealthNet` 규칙 준수(model_id 우선 → 종/이름 별칭, 한글 "바질" 포함). **세션 단위 train/val 스플릿**(같은 세션 프레임이 양쪽에 걸치지 않음 — 상관관계 누출 방지), 라벨별 세션 ≥3개일 때 최신 세션 1개를 val로. 데이터셋은 파생물이라 매 실행 시 재생성 |
| `ml/plant_health/finetune_diagnosis.py` 신규 | 배포된 `basil.bin` 로드 → 합성 리플레이 16/클래스 + 사람 라벨 프레임 최대 24/클래스 혼합 파인튜닝(`finetune_web.py`의 `step()` 재사용, lr decay ×0.9, val 최고 스냅샷) → 재저장 + round-trip 검증(재로드→추론 1회) + `metrics/finetune_diagnosis.{json,md}` before/after 리포트 |
| `ml/plant_health/test_personalization.py` 신규 | E2E: 실제 백엔드 스키마 시드 DB + 실제 프레임 JPEG로 전체 루프 검증. **배포 `basil.bin` 백업/복원 필수 포함**(테스트 후 byte-identical 검증). 엣지 케이스: 한글 종 별칭 세션 포함 / 비대상 종(딸기) 제외 / 프레임 파일 없는 세션 스킵 |

**동작 흐름 (운용 시나리오):**
1. Master "영상 건강진단" → Slave 진단 세션 업로드 → 사람 라벨 확정 → DB에 누적
2. `python ml/plant_health/export_diagnosis.py --species basil` → `diagnosis_data/` 재생성
3. `python ml/plant_health/finetune_diagnosis.py --species basil` → `basil.bin` 개인화 + 메트릭스 확인
4. APK 재빌드(`build-apk-dist`) → Farmer에 새 모델 탑재

**검증:**
- `python ml/plant_health/test_personalization.py` — **16/16 어설션 통과** (export 45장/skipped 3/스플릿 무누출/val 최신 세션 선택/파인튜닝 before 50%→after 100%/roundtrip OK/`basil.bin` 복원 byte-identical)
- 실제 로컬 DB(진단 테이블 없음)에서 graceful 확인 — 경고 출력 + exit 0; 데이터 없을 때 finetune은 메시지 + exit 1
- `basil.bin` 무결성 — 실 확정 라벨이 없어 원본 유지(HEAD 해시 `2ad462cb…` 일치). 가짜 개인화를 출하하지 않음
- `git push origin main` — `5e8e9d3..c885cd8` 완료
- **GCP 배포**: VM pull(c885cd8 포함 전체 커밋 반영) + `npm install` + `systemctl restart oojoo` → **`active`**
- 헬스체크: `GET /health` → 200 `{"ok":true,"service":"oojoo-farm"}` ✅
- **`GET /api/diagnosis/plant/nonexistent` → 200 `{"sessions":[]}`** — 진단 세션 API가 GCP에서 라이브 ✅ (기존 404 → 라우트 마운트 확인)

**GCP 배포 인프라 복구 (이 PC에는 배포 수단이 없었음):**
- gcloud 미설치·SSH 키 없음(known_hosts는 github.com뿐)·서비스 어카운트 키 없음 → 이전 배포는 별도 머신(DESKTOP-B68U2MG, 프로젝트 키 사용자 `wooju-family`)에서 수행된 것으로 확인
- `winget install Google.CloudSDK`(589.0.0) + 브라우저 `gcloud auth login`(`cklee.brain@gmail.com`) + `gcloud config set project oojoo-farm`
- `gcloud compute ssh`가 PuTTY PPK를 요구(plink/puttygen은 본PC에 없음) → **gcloud API로 인스턴스 메타데이터에 `wooju-family` SSH 키 추가**(기존 프로젝트 레벨 키 보존 — 인스턴스 레벨은 additive) 후 **순수 OpenSSH 직접 접속**으로 배포. 향후 배포도 이 경로 재사용 가능

**남은 작업:**
- 실기기 E2E: 실 카메라 영상으로 MediaCodec 디코딩·YUV 변환·프레임 품질 확인 (10-18 일지 이월)
- 확정 라벨이 수개 세션 쌓이면 export → finetune 실행 → APK 재빌드 (`build-apk-dist`)
- 루트 `PC` 파일(8/6일자 스크립트 실수 산출물, 64B) 삭제 여부 결정
- Phase 6 잔여: FCM 푸시, 종 추가, 모델 개인화 자동화(현재는 수동 실행)

**특이사항:**
- 서브에이전트 위임 인프라가 모델 구성 오류(`ProviderModelNotFoundError: opencode/gpt-5-nano`, 카테고리 모델 미설정)로 전면 다운 → 전 컨텍스트 로드 후 직접 구현으로 전환
- grep 도구가 UTF-8 한글 포함 Kotlin 파일에서 오탐("No matches") — `FarmerEngine.kt`의 `diagnose_video` 구현을 read로 재확인해야 했음. 한글 포함 파일 검색 시 read 권장
- `gcloud auth login`은 브라우저 인증 후에도 셸이 종료되지 않아 타임아웃 종료 — 자격 증명은 정상 저장됨(`gcloud auth list`로 확인)
