# 작업 일지 — 2026-11-20

## OOJOO-FARM 미진/부족 부분 완성 (W1–W7 전체 웨이브) + Build 2026112010

**요구사항:** "미진한 부분을 완벽하게 개발해. 그리고 부족한 부분도 개발해."
백엔드 게이트 `npm test`(61/61) 유지·확장, Android 양 앱 `compileDebugKotlin` RC=0 유지,
unit당 커밋(푸시는 사용자 판단에 맡김).

### W1 — 백엔드 버그 (7커밋)
users PII(패스워드 해시 노출) 제거, 관리자 통계 SQL 수정, 죽은 `/dashboard` 루트 제거,
`watering/command`(호출 0건) 제거(+Kotlin DTO), 4xx passthrough + crash_logs 5xx 전용,
graceful shutdown, 경계 검증 라이브러리 `src/lib/validate.js`.

### W2 — 백엔드 PRD API (12커밋)
명령 취소, 수확 API, 이벤트 배치, 프로필 PUT+관심사, 슬레이브 별명 변경, 구독 강제,
피드 반경 5km + 좌표 제거, sweeper+unpair 정리, 알림 읽음 상태, capture 하한 10분 + pest_interval.

### W3 — DM 백엔드
1:1 쪽지(회화+메시지, 읽음 처리) — PRD 4.9. 테스트 28→61.

### W4 — 마스터 UI (A–G 7배치)
A 홈 대시보드 실데이터화(가짜 값 제거) · B Farmer 6필드 정책 편집+별명+명령이력 ·
C 수확 기록+정밀 mL 관수 확인 · D 무한 스피너→에러+재시도 · E 프로필+DM+알림 deep-link ·
F 라이브카메라/체크아웃 i18n 보정 · G 죽은 온보딩 라우트+8.3MB GLB+SpeechBubble 삭제.

### W5 — Farmer 안전·신뢰성 (A–G 7커밋)
A **관수 범람 수정**(30s 틱마다 재관수≈36L/h → 상승 엣지 히스테리시스+30분 쿨다운) ·
B **BLE 오프라인 거짓 관수 수정**(`water_failed` 이벤트)+자동 재연결(5s/15s 스캔타임아웃) ·
C 오프라인 큐 Mutex 직렬화(lost update 방지) · D **capture_interval/pest_interval 정책 실반영**
(하드코딩 20일/15분 제거, PolicyResponse에 pest_interval 추가) · E **명령 done 정확화**
(fire-and-forget 작업 완료 시점에 보고, water는 attemptWater 대기) · F ROI 루프 식물별 관수 귀속 ·
G 배터리 20% 이하 `battery_low` 이벤트(하락 엣지+1h 쿨다운).

### W6 — ML 증거 + cactus 재학습
`test_personalization.py` **16/16 통과**(export→split→finetune→restore byte-identical, 2회 실행).
가장 약한 cactus(val 0.611) 재학습: `train.py --seed` 추가(기본 7=역사적) + 10-seed 스윕,
**고정 독립 holdout(600프레임)으로 교차 확인**(seed7 0.658 vs seed99 0.680 — 운 좋은 val 스플릿 아님 검증).
seed 99로 재학습 → **val 0.685**. OJP1 구조 exact-fit + 가중치 유한 + `load_ojp1` 로드/추론 확인 + APK 내 sha256 일치.

### W7 — 문서·빌드
README 정정: OJP1 **int8→float32**, "452장 CC 사진"→452장=CC 사진+합성 fill 혼합 세트(360/92 스플릿,
평가 ~92장 test), ML 트리 스크립트 6개 추가, 온보딩 항목 제거(스크린 삭제됨), Node 22+ 명시.
backend/README: Node 22+, 삭제된 watering/command 제거, action 9종+cancel, 정책 필드 snake_case 정정.
`start-backend.bat`(%~dp0backend→scripts/backend 오류)·`run-emulator.bat`(ROOT=scripts/ 오류,
APK 이름 불일치) 경로 수정. versionCode/Name → **2026112010/1.0.2026112010**,
`assembleDebug` 양 앱 → dist/ (마스터 30.5→18.1MB, 슬레이브 23.9→18.3MB).

### 커밋 (main, 총 35개 — W1 7 + W2 12 + W3 1 + W4 7 + W5 7 + W6 1 + W7 1, docs/builds·diary 포함)
```
23fc26b docs: fix stale README/backend-README claims + Windows launcher paths
4838237 perf(slave): retrain cactus model with tuned seed (val 0.611 -> 0.685)
5514d17 feat(slave): low-battery alert event (falling edge + cooldown)
297f1b5 fix(slave): per-plant auto-watering in ROI monitoring (attribution)
3d1f22f fix(slave): don't mark commands done before they complete
a922c25 fix(slave): wire capture_interval + pest_interval policy into scheduling
0a0c36b fix(slave): serialize offline queue read-modify-write (lost updates)
94c23bb fix(slave): no false 'watered' when BLE offline + auto-reconnect
869bdae fix(slave): auto-watering cooldown + hysteresis (flood safety)
e4bcf79 chore(master): remove dead onboarding route, 8.3MB GLB assets, SpeechBubble
bd96399 fix(master): close i18n holes in live camera and cart checkout
d60ceaf feat(master): profile screen, DM (conversations+thread), notification deep-link
04b7d4d fix(master): replace infinite spinners with error + retry
62b210d feat(master): harvest records on plant detail + quick-water confirm
353a871 feat(master): farmer screen - full policy editor, rename, command history
9a2da6e fix(master): real data on home dashboard - drop placeholder and fake values
790957e feat(backend): 1:1 DM messages (PRD 4.9 '쪽지', Phase 3)
… (W1 7 + W2 12: 9fb30a1…cafbef5, 25fe69b…d15b737)
```

### 검증 (전체 게이트, 마지막 확인)
- 백엔드 `npm test` **61/61** (Node 22.23.5)
- `:master:app:assembleDebug` / `:slave:app:assembleDebug` 성공, aapt 확인 versionCode 2026112010
- `:master:app:compileDebugKotlin` / `:slave:app:compileDebugKotlin` RC=0
- `ml/plant_health/test_personalization.py` 16/16
- `node scripts/verify-locales.mjs` 2 모듈 패리티
- CJK 한자 오염 grep 클린 (수정 파일 전수)
- 결정 로그: `.dev-run/decisions.tsv` (W4/W5/W6 행 추가)

### 남은 작업 (사용자 판단 필요)
- **FCM 푸시**: google-services.json 없음 — 미구현(README/PRD에 명시)
- **실제 결제 PG**: 데모 체크아웃 유지 — 실 PG 연동 미구현
- **실기기 E2E**: BLE 실 하드웨어/실 카메라 경로 이 호스트에서 미검증(컴파일+시뮬레이션만)
- **개인화 루프 실데이터**: 확정 라벨이 쌓이지 않아 basil.bin 원본 유지(가짜 개인화 출하 안 함)
- 마스터 API 비인증 노출(지역 기반 공개 API 설계 선택 — PRD와 정합, 변경은 질문 필요)
- **GCP 배포/푸시**: 사용자 판단에 맡김 (로컬 main 커밋만)

### 특이사항
- FarmerListScreen.kt는 기존 CRLF/LF 혼합(178번 라인 LF) → 편집 도구 라인별 매칭 실패, 바이트 단위 Python replace 사용
- cactus 재학습 시 "운 좋은 seed" 아님을 보장하기 위해 고정 독립 holdout(rng 2026) 교차 평가 —
  val 스플릿 자체가 seed마다 달라지므로 필수
- dist/ 마스터 APK 12MB 경량은 Batch G GLB 삭제 효과(기존 APK가 죽은 자산을 포함했던 것)

---

## Addendum — W8: Phase 6 코드 완결 (2026112011, 2026-11-20)

지시 "개발의 완성도가 가장 높은 결정을 해"에 따라 위 "남은 작업" 중 4개를 코드 완결 + 설정 게이트로 닫았다.

### FCM push (완결, config-gate)
- 백엔드 `src/lib/push.js`: FCM v1 HTTP API, node:crypto RS256 JWT(의존성 0), data-only 메시지.
  `FCM_PROJECT_ID`+`FCM_SERVICE_ACCOUNT` env 채우면 즉시 활성, 미설정 시 no-op.
- `dispatchMasterAlert()` — SSE chokepoint(단건+배치) 병행. 대상 = SSE 4종 + `water_failed` + `battery_low`.
  수신 = 소유자+가구원 전체 디바이스(`users.push_tokens`, 최대 10).
- 마스터: google-services **플러그인 없이** `FirebaseCfg`(빈 값=비활성) + `OojooApp` 수동 초기화,
  `FcmService`는 기존 `notiLabel`/`notiColor` 로 로컬 알림 렌더링(인앱과 동일). 로그인 전 토큰은 pending 보관.
- 테스트 6건 추가 (JWT 서명 공개키 검증 포함). npm test 61→67.

### Toss Payments (완결, config-gate)
- `PG_MODE` 스위치 — 기본 demo(기존 동작 바이트 동일). toss: `pending_payment`(재고 미차감) →
  서버가 결제 생성(`POST /v1/payments/requests`, secret key) → `approvalUrl` → 브라우저 승인 →
  confirm(`POST /gateway/payments/{key}`) / 웹훅(HMAC `tss_v1:hmac_sha256(secret, Toss-Timestamp+rawBody)`,
  raw 바디라 `express.json()` 전에 `express.raw` 마운트) / 폴링 조회 중 먼저 도착한 것으로 멱등 `paid`.
  `markOrderPaid()` 단일 chokepoint — 경쟁 포함 재고 차감 1회. 취소는 `canceled`.
- **디자인 변경(의사결정)**: Toss Android SDK(`com.toss:tosspay-android`)가 가용 리포지토리(Maven Central/Google
  Maven)에 없고 docs 미달성이므로 SDK 의존 대신 **서버 주도 approval-url 플로우** — 앱에는 Toss 키가 전혀 없음
  (SDK 방식보다 보안적으로도 강함).
- 마스터: 체크아웃 `pending_payment` → approvalUrl 브라우저 + 3초 폴링(2분). 확정 시에만 장바구니 비움.
- 테스트 16건 추가. npm test 67→83.

### W5 안전 로직 시뮬레이션 테스트 (실기기 E2E 대안)
- `FarmerEngine.kt` W5 미러를 `simulation/src/engine/wateringSafety.js` 로 순수 상태머신 이식
  (엣지 히스테리시스/30분 쿨다운/성공 시에만 엣지 소모/수동도 기준점 갱신/water_failed/battery_low).
  `FarmerSim` water 명령이 실제로 이 경로를 쓴다.
- 테스트 9건 — 시뮬레이션 게이트 15→24. 범람 수정이 이제 디바이스 없이도 증명됨.

### 개인화 원커맨드
- `personalize.sh --species X` (export→finetune→round-trip, 무데이터 거절 exit 2).
  양쪽 경로 실검증(빈 DB 거절 / 시드 DB → val 0.5→1.0 → 복원). `export_diagnosis.py` 경고는 stderr 로 이동.

### 빌드 2026112011
- versionCode/Name 2026112011 양 앱, dist/ 재빌드 (마스터 +0.8MB = Firebase deps).
- aapt 확인, 슬레이브 APK cactus.bin sha256 = 재학습 모델 일치, 마스터 manifest FcmService 확인.
- 문서: README/PRD/backend README Phase 6 = code-complete + config-gated, `.env.example` FCM/PG env,
  `docs/builds/2026112011.md`.

### 여전히 호스트 한계 (의도된 config-gate — 미완결이 아님)
- FCM 실동작(실 Firebase + 실 디바이스), Toss 실결제(실 PG 계정), 개인화 실데이터(실 사용 라벨),
  BLE/카메라 실기기 E2E.
- 마스터 API 비인증 노출 — PRD 공개 피드 설계 유지(변경 필요 시 별도 질문).
