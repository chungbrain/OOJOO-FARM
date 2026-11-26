# 2026-11-26 최근 이벤트 통계·시계열 + 성장 스토리 카드 크기 맞춤 (event-stats-and-clip-card)

## 요구사항

1. 성장 스토리 숏클립을 성장 사진첩과 **같은 크기**로 보여줄 것.
2. 최근 이벤트를 **통계 먼저**, 그리고 **시계열 그래프**로 보여줄 것.

## 문제 원인

- 숏클립은 사진첩 카드 아래에 높이 280dp 고정 `VideoView` 띠로 붙어 있었음. 클립은 세로 720×1280(9:16)이라
  좁고 작게 보였음.
- 최근 이벤트는 20건 타임라인 목록(약 1,100dp)뿐 — 기간·유형별 경향을 볼 수 없었음.
  게다가 서버 `GET /api/events/:slaveId`가 **최신 50건만** 반환 → 그대로 일별 집계하면 오래된 날이
  실제보다 적게(0건으로) 보이는 문제가 생김. 시각도 UTC 원문(`created_at`) 그대로 표시.

## 변경 내역

| 파일 | 내용 |
|---|---|
| `backend/src/routes/events.js` | `?days=N`(정수 1..90) 선택 파라미터 — 최근 N일 이벤트 전부(상한 1000) + 50건 미만이면 최신 50건으로 채움. 응답에 `days`, `truncated`. 파라미터 없음/잘못된 값은 기존 계약 그대로 |
| `backend/test/events_window.test.js` (신규) | 창 계약 테스트 5건 |
| `android/master/.../ui/EventStats.kt` (신규) | `EventStatsCard` — 통계 → 일별 시계열 → 유형별 → 최근 기록. 집계 순수 함수 `computeEventStats`, `niceAxisMax` |
| `android/master/.../ui/PlantDetailScreen.kt` | 이벤트 20건 목록 → `EventStatsCard`. 숏클립을 별도 `GrowthClipCard`로 분리, 사진첩 카드 실측 높이(`onSizeChanged`)와 같게. ViewModel이 `days=14`로 조회하고 `eventsComplete` 판정 |
| `.../network/ApiService.kt`, `.../model/Models.kt` | `events(slaveId, days)`, `EventsResponse.days/truncated` |
| `.../data/AppLocale.kt`, `notiLabel` | `water_low`(💧 수분 부족), `pest_control`(🛡️ 해충 대응) 라벨 추가 — 기존엔 원문 type 노출 |
| `.../ui/GrowthAlbum.kt` | 날짜 포맷(`AlbumFormats`)을 internal로 — 이벤트 카드에서 재사용 |
| `values/strings.xml`, `values-en/strings.xml` | 신규 23개 |
| `android/master/app/src/test/.../ui/EventStatsTest.kt` (신규) | 집계 테스트 6건 |

## 새 화면 구성

**성장 스토리 카드**: 사진첩 카드와 가로·세로 동일(실측 높이 동기화). 🎬 제목 + 부제 + 검은 프레임 안에
9:16 영상을 비율 유지·가운데 정렬(`wrapContentSize`로 VideoView가 스스로 크기 결정).

**최근 이벤트 카드** (위에서 아래로):
1. 헤더 `📊 최근 이벤트 — 최근 14일 · N건` (구버전 서버면 "최근 50건 기준 집계" 안내)
2. 그룹별 KPI 타일 3개 — 관수 / 해충 / 생육·기기 (그래프 범례 겸용)
3. 일별 누적 막대 그래프 — 탭·가로 드래그로 날짜 선택 → 그날 그룹별 건수 표시, 선택 막대 위 합계.
   `표로 보기`로 같은 데이터를 표로 전환
4. 유형별 순위 막대 (상위 5 + 기타, 막대 색 = 그룹 색, 값은 막대 끝)
5. 최근 기록 5건 (로컬 시각, `더 보기`로 최대 30건)

- 이벤트 그룹: 관수(auto/manual_water, water_low, water_failed), 해충(pest_detected, pest_control),
  생육·기기(health_change, harvest_ready, anomaly, battery_low, capture, 그 외)
- 날짜 버킷은 기기 로컬 시각 기준 (서버는 UTC 저장)
- 색: dataviz 기본 팔레트 슬롯 1~3(#257de0/#f47230/#00b894) — `validate_palette.js --pairs all`
  흰 카드 기준 CVD ΔE 12.6, 일반시 ΔE 23.0 통과. 주황·청록은 대비 3:1 미만이라 이름·숫자 라벨과 표 보기를 항상 제공
- 막대: 폭 ≤ 칸의 60%(최대 24dp), 위쪽 4dp 라운드·바닥 각짐, 누적 조각 사이 2dp 틈, 1px 실선 격자

## 검증 결과

- `node scripts/verify-locales.mjs` ✅
- 백엔드 `node --test test/events_window.test.js` ✅ 5/5, 전체 `npm test` 114/115
  — 실패 1건(app.test.js SIGTERM graceful shutdown)은 변경 전 코드에서도 동일하게 실패하는 Windows 환경 이슈
- `:master:app:testDebugUnitTest` ✅ 15/15 (EventStats 6, GrowthAlbumDate 4, 기존 5), 신규 파일 경고 없음
- `./scripts/build-dist.sh` ✅ → `dist/oojoo-farm-master-debug.apk` (19,879,508 bytes). farmer APK는 코드 변경 없어 기존 유지
- 화면 실물 확인 **미실시** — 에뮬레이터 없음. Paparazzi(JVM 스냅샷) 임시 도입은 자동 승인 정책에서 차단되어 진행하지 않음

## 배포 주의

- 백엔드 변경은 GitHub push만으로는 GCP(34.50.40.104)에 반영되지 않음 → VM에서 `git pull` + `oojoo.service` 재시작 필요.
  반영 전에도 앱은 동작함(구버전 응답이면 최신 50건 기준으로 창을 줄여 집계하고 안내 표시).
- master APK 서명 키는 이전 빌드(2026112601)와 같음(이 PC 키) — 2026112601을 설치했다면 업데이트 설치 가능.

## 커밋

- `822494e feat(backend): GET /api/events/:slaveId optional ?days=N window`
- `06e3029 feat(master): event stats + daily chart, growth clip card matches album size`
- `build: debug APKs 2026112602 with event stats and clip card`

## 남은 작업

- 실기기에서 그래프 탭/드래그, 표 전환, 숏클립 카드 높이 확인
- GCP 백엔드 재배포
- 이벤트를 식물별로 걸러 볼지 결정 (현재는 담당 Farmer의 전체 이벤트 — 기존 동작 유지)
