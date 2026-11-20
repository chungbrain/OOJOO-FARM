# 작업 일지 — 2026-11-20

## 요구사항 3건 완결: 에뮬레이터 자동반영 + 모니터링 페이지 + 식물별 on-device AI 14종

**요구사항:**
1. "emulator를 만든다. 코드가 수정되거나 app에 변화가 있을때 emulator에 반영되어야 한다."
2. "backend에 monitoring page를 만들어서 관리되도록 해야한다. (master 앱 활동, farmer 활동,
   master-farmer 관계, 마켓, 커뮤니티)"
3. "farmer가 다양한 식물을 모니터링 할수 있도록 식물별 ai 모델을 on device로 개발.
   학습 데이터도 필요하다."

**커밋 (5 + 1, 푸시 없음 — 사용자 hold):**
`a261371` 모니터링 페이지 → `111493a` 종 레지스트리+데이터셋+mojibake 수정 →
`2b0b8d8` pipeline+14종 재학습 → `661b211` 패리티 테스트 → `faaff73` 빌드 2026112012 문서 →
`5db5ae2` emulator tooling

### 요구사항 1 — 에뮬레이터 자동반영
- KVM 없는 호스트(EC2) → x86_64 AVD(`oojoo-api34`, API34 google_apis) + QEMU TCG 강제.
  `ANDROID_I_WANT_MY_TCG=yes` + `-accel off` 필수 (emulator 37.3.10.0 하드 에러 — 에러 메시지가
  env 이름을 명시). arm64 이미지는 sdkmanager 저장소에 없음. 첫 부팅 825711ms.
- `scripts/emulator/`: `emulator.env`(공통), `emulator-up.sh`(멱등 기동+권한 위임),
  `sync-apps.sh`(gradle 증분+install+재시작+증빙), `watch.sh`(mtime 폴링 10s/쿨다운 45s/
  pidfile 단일 인스턴스, inotify 없음 대응).
- TCG에서 런타임 권한 다이얼로그가 GrantPermissionsActivity로 스택 경직(실측) →
  전 위험 권한 `pm grant` 사전 위임. headless Compose는 uiautomator 불가 →
  `dumpsys package` versionCode/lastUpdateTime 을 동기 증빙으로 채택.
- **최종 증빙**: 2026112012 빌드 시 워처가 최종 자산(14종 재학습 .bin)+버전업 변경을 감지해
  gradle 734ms → 자동 설치, master/slave dumpsys 모두 versionCode=2026112012
  (master 04:08:32 / slave 04:07:52). 2026112011→2026112012 변화가 자동설치로 확인.

### 요구사항 2 — 모니터링 페이지 (`a261371`)
- `GET /api/admin/dashboard` (overview/master/farmer/relations/market/community/system
  단일 JSON, 스키마 추가 0) + `GET /admin` self-contained dark 대시보드(CDN 없음 —
  호스트 웹 차단, 15초 자동 새로고침). `/stats` 와 집계 헬퍼 공유(drift 방지).
- 페이지는 `backend/src/admin.html` — `backend/.gitignore` 가 `public/` 의도적으로 무시
  (커밋 d1fb743)하므로 src 옆에 배치. PRD 신뢰 모델(마스터 API 비인증) 유지.

### 요구사항 3 — on-device AI 14종 + 학습 데이터
- 커버리지: 마스터 등록 화면 16 라벨 = 14 고유종. 누락 6종(lettuce/perilla/green_onion/
  sweet_potato/potato/onion) 추가 → 100% 커버. 마스터 변경 불필요(EN map+emoji+ALIAS 기존 커버).
- **학습 데이터**: `gen_dataset.py` — 종별 600장(6클래스×100) 48×48 PNG, 클래스별 RNG
  `[seed, si, li]` → 동일 시드 byte-identical 재생성. manifest(SHA-256 클래스 다이제스트)+
  샘플 2장/클래스 커밋, 전체 세트 gitignore+`--verify`.
- **원커맨드**: `pipeline.sh` dataset→verify→train(--from-dataset)→assets, val_acc≥0.75 게이트.
  14종 최종: 0.759–0.907 (onion 0.907 최고, basil/herb/lettuce 0.759 최저).
- **잠재 버그**: `PlantHealthNet.kt` UTF-8 이중인코딩(mojibake) — 한글 ALIAS 키/LABEL_KO 전부
  깨져 종 해석("상추"→lettuce)이 조용히 사망 중. 전체 저장소 전수 스캔(손상 파일 1개) 후
  코드 byte-identic 재작성(git diff로 비-ASCII 라인만 변경 확인), 라벨은 마스터 i18n 정합.
- **패리티**: `make_vectors.py`(14종 .bin + 고정 입력 logits) → sim 테스트 3건
  (asset 구조/labels.json/Python↔JS ±1e-3) → node --test 27/27.

### 문제 원인과 해결
| 문제 | 원인 | 해결 |
|---|---|---|
| x86_64 에뮬레이터 즉시 종료 | KVM 없으면 하드 에러 | `ANDROID_I_WANT_MY_TCG=yes`+`-accel off` (arm64 이미지 부재 확인) |
| cactus val 0.5 (기본 레시피) | column 모양 14ep로 약분별 (10시드 최대 0.59) | ds99+30ep+lr0.05 → 0.815 (종별 오버라이드) |
| sprout/blade 종 val 0.44–0.59 | 잎 영역 작아 GAP에서 증상 신호 희석 + 구근 블롭이 heat 틴트와 색 충돌 | 페인터 잎 확대(8장/폭·크기↑)·구근 축소 → green_onion 0.593→0.815, 나머지 종별 seed 검색 |
| perilla 발산(overflow→NaN) | lr 0.03에서 기울기 폭발 | lr 0.02 (seed 99 → 0.852) |
| pipeline 2회 실패 | (1) fail-var 누수 (2) **데이터셋 시드/학습 시드 혼선** — 검색한 train seed를 ds seed 테이블에 기입 | fail 플래그 격리 + SPECIES_SEED(ds)/SPECIES_EXTRA(train) 분리. 게이트가 2개 버그를 모두 포착 |
| gitignore 1-tier 패턴 | `datasets/*/*.png` 가 실제 2-tier 경로(`datasets/<sp>/<label>/`) 미일치 → 8400장 전부 커밋됨 | `datasets/*/*/*.png`+샘플 negation, soft reset 후 재커밋 (미푸시라 안전) |
| 한자 오염 2건(빌드 문서) | 全覆盖/实测 오타 | 수정 + 전수 grep 클린 |
| make_vectors batch 차원 | (1,6) logits → scalar 변환 실패 | `n[0].mean(axis=(0,1))` |
| adb "more than one device" | TCG 하 transient | `-s emulator-5556` 명시, 워처 재시도 로직이 커버 |

### 변경 파일 (주요)
- `scripts/emulator/{emulator.env,emulator-up.sh,sync-apps.sh,watch.sh}` (신규)
- `ml/plant_health/{gen_dataset.py,pipeline.sh,make_vectors.py}` (신규),
  `species.py`(14종), `train.py`(--from-dataset+rosette/blade/sprout), `datasets/`(샘플+manifest)
- `android/slave/.../vision/PlantHealthNet.kt` (mojibake 수정+14 ALIAS),
  `assets/models/*.bin` (14종 재학습), `build.gradle.kts` ×2 (2026112012)
- `simulation/src/vision/plantScene.js` (14종+shape), `simulation/test/{species_models.test.js,
  vectors_species.json}` (신규)
- `backend/src/{routes/admin.js,app.js,admin.html}`, `backend/test/admin_dashboard.test.js` (a261371)
- `README.md`, `docs/prd.md`, `docs/builds/2026112012.md`, `backend/README.md`, `dist/*.apk`

### 검증 결과
| 게이트 | 결과 |
|---|---|
| backend `npm test` (Node 22) | 85/85 |
| simulation `node --test` (Node 18) | 27/27 (14종 패리티 포함) |
| gradle assembleDebug (master+slave) | RC=0 |
| `scripts/verify-locales.mjs` | pass (2 module) |
| CJK 한자 grep (변경 파일 전수) | clean |
| `pipeline.sh --all` | 14/14 OK (게이트 0.75) |
| aapt badging | 양 APK versionCode 2026112012 |
| 모델 유한성 | 14종×5022 파라미터 nonfinite 0 |
| 에뮬레이터 | 워처 자동설치로 versionCode 2026112012 (dumpsys) |

### 남은 작업
- GitHub push — 사용자 지시 대기 (현재 main이 origin 48 ahead, 미푸시)
- 인터넷 실사진 eval(`eval_internet.py`) — 웹 차단 호스트에서 불가, 문서화된 알려진 한계
  (synthetic→real 10–25%, 게이트 아님)
- 에뮬레이터 워처는 세션 백그라운드에서 가동 중 (pid `.dev-run/emulator-watch.pid`)
