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
- GitHub push — 사용자 지시 대기 (현재 main이 origin 50 ahead, 미푸시)
- 인터넷 실사진 eval(`eval_internet.py`) — 웹 차단 호스트에서 불가, 문서화된 알려진 한계
  (synthetic→real 10–25%, 게이트 아님)
- 에뮬레이터 워처는 세션 백그라운드에서 가동 중 (pid `.dev-run/emulator-watch.pid`)

## wave 후 부록: 에뮬레이터 포트 → 8989 (2026-11-20)

- 요청: "8989로 해줘" (5555 문의 후. 5555는 이 호스트 root `nv-hostengine`(NVIDIA DCGM,
  6일+ 가동)이 점유 중 — root 프로세스라 종료 불가/비추천).
- 측정: emulator 37.3.10.0에서 `-port N`은 **ADB 포트 자체** (console = N+1).
  `-port 8988` → `emulator-8988`. 권장 범위 [5555,5586] 밖이라 WARNING 로그하나
  transport 등록/동작 정상.
- 함정 2개:
  1. 강제 종료된 에뮬레이터의 **유령 transport** (`emulator-5554`)가 adb 서버에
     남음 (진짜 프로세스 없음) → bare adb "more than one device".
  2. ADB를 bash **함수**로 바꾸면 호출처의 `"$ADB"` 변수 전개에서 함수명이
     빈 문자열로 전개됨 (함수명은 변수 아님) — 호출처 12개가 전부 `: command
     not found`으로 죽을 뻔. 소싱 직후 헬퍼 테스트(`device_ready` rc=1)가 포착 →
     함수명을 변수에 보관하는 포인터 패턴으로 수정 (호출처 0 변경).
- 변경: `emulator.env` (`EMU_PORT=8989`/`EMU_SERIAL`, ADB 포인터),
  `emulator-up.sh` (`-port "$EMU_PORT"`), `docs/builds/2026112012.md` 구동 명령.
- 검증: `emulator-8989` 기동, device_ready/boot_completed/pkg_info OK,
  dumpsys master/slave `versionCode=2026112012` (lastUpdateTime 04:08:32/04:07:52
  유지 — userdata 영속, 재설치 불필요), 양 앱 프로세스 가동.
- 커밋: `04a75f7` (chore(scripts): pin emulator ADB port 8989, serial-pinned adb).
  미푸시 (사용자 지시 대기).

## wave 후 부록 2: 갤러리 oojoo 폴더 + 실시간 카메라 스트리밍 (2026-11-20)

- 요청: "farmer의 사진첩이 폰의 사진첩의 특정 oojoo폴더에 저장되고 열람
  가능하도록 해. 그리고 farmer에서 카메라 보기 기능이 3초였는데 실시간
  스트리밍도 되도록 지원했으면 좋겠다."
- 설계 (5개 단위): U1 backend `slaves.stream_url`(하트비트 보고) →
  U2 slave `GalleryPublisher`(MediaStore `Pictures/oojoo`, `PlantAlbum.add`
  단일 초크포인트 후킹) → U3 slave `MjpegStreamer`(raw ServerSocket :8613,
  multipart/x-mixed-replace ~5fps, 프레임은 기존 ImageAnalysis 콜백에서
  640w q70 발행) → U4 master LiveCamera "실시간 보기"(WebView `<img>`,
  3초 캡처는 그대로) → U5 통합 검증 + 빌드 2026112013.
- 검증 (디바이스 실측):
  - R-A: `capture_photo` 명령 + 기동 성장 캡처 → MediaStore
    `bucket_display_name=oojoo`, `relative_path=Pictures/oojoo/`,
    `is_pending=0`, 1856×1392 JPEG + 백엔드 업로드 동일 바이트.
  - R-B: 하트비트 → DB `stream_url=http://10.0.2.15:8613/stream.mjpg` →
    마스터 API 반환 → 호스트 `adb forward tcp:8613` 경유 MJPEG 실수신
    (11 image/jpeg 파트/8초, 480×640 JFIF). 서비스 `isForeground=true
    types=0x41`.
  - 한계: 에뮬 가상카메라 정적 → 프레임 MD5 동일(파이프라인은 전구간
    검증됨), 마스터 WebView 렌더는 headless에서 직접 확인 불가
    (클리어텍스트는 매니페스트 허용 확인).
- 검증 중 발견·수정한 결함 4건:
  1. **FGS 10초 윈도우 크래시 루프 (TCG 한정)** — 콜드 스타트에서 Compose
     첫 프레임 2~3분간 메인 스레드 혼잡 → `ForegroundServiceDidNotStartInTimeException`
     반복. 3단계로 해결: onCreate 즉시 startForeground + 엔진 기동
     백그라운드 스레드 (`dce663d`), UI 재시도 루프+기동 시간차 (`e357d75`),
     **포그라운드에서 일반 startService() (10초 윈도우 자체가 없는 경로) →
     onCreate에서 FGS 승격**, sync-apps는 슬레이브를 맨 위로 (`bd70e44`).
     실기기 영향 없음(밀리초 기동).
  2. **워처 backend_restart** — pkill 패턴이 구 프로세스를 놓쳐 health가
     위장 "restarted OK". regex+퇴출대기+pid 생존 확인으로 수정 (`90721ba`).
  3. **에셋 죽은 IP** — 양 앱 server_config.yaml이 `34.50.40.104` (주석은
     10.0.2.2라 해놓고) → 신규 설치 전부 죽은 서버. 10.0.2.2로 (`dce663d`).
  4. **백엔드 하드 킬** — 1시간 2회, 로그 없이 소멸. 워처에 60초 헬스
     와치독(2회 연속 실패 시 재기동) 추가 (`8446e1b`).
- 함정: 호스트가 게스트 IP(10.0.2.15) 직접 접근 불가(NAT 모드) —
  스트림 확인은 `adb forward` 경유. `nc` "Network is unreachable"는
  게스트 네트워크 미상 (system_server 사망 직후) 이기도 했고, 그 뒤
  "ConnectException"이 났을 때는 실제로 호스트 백엔드가 죽어 있던 것 —
  같은 증상, 다른 원인 2개.
- 커밋: `6ab588d` `e7f695b` `efae78c` `c811506` `53f683d` `dce663d`
  `90721ba` `e357d75` `bd70e44` `8446e1b` + 빌드 2026112013 (최종 커밋).
  미푸시 (사용자 지시 대기).
- 남은 작업: GitHub push 대기. `loadPlant()` 엔진 기동 1회 동기화 —
  마스터가 나중에 식물 등록하면 슬레이브 재기동까지 미반영 (기존 동작,
  개선 후보).
