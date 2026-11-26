# 2026-11-26 식물 상세 성장 사진첩 UI 개선 (growth-album-redesign)

## 요구사항 / 문제 원인

- 식물카드 → 식물 상세의 **성장 사진첩**이 사진이 쌓일수록 아래로 끝없이 길어짐 → 사용자 친화적 디자인으로 개선.
- 원인: `PlantDetailScreen`이 `photos.chunked(3)`로 3열 그리드 행을 사진 수만큼 이어 붙였음
  (원본 이미지를 그대로 썸네일로 로드, 상세 보기 없음, 날짜 문자열만 나열).

## 변경 내역

| 파일 | 내용 |
|---|---|
| `android/master/.../ui/GrowthAlbum.kt` (신규) | `GrowthAlbumCard` — 높이 고정 카드. 대표 사진 페이저 + 썸네일 띠 + 성장 재생 + 전체 보기 그리드 + 전체 화면 뷰어 |
| `android/master/.../ui/PlantDetailScreen.kt` | 기존 3열 그리드 블록 제거 → `GrowthAlbumCard` 사용. 관심영역/숏클립 버튼은 카드 footer 슬롯으로 이동, 숏클립 영상 플레이어는 카드 아래 유지 |
| `values/strings.xml`, `values-en/strings.xml` | 신규 문자열 14개 (장수, 전체 보기, 성장 재생/멈춤, 기간 요약, D+N, 이전/다음, 크게 보기, 확대 힌트, 날짜 포맷 패턴 3종) |
| `android/master/app/src/test/.../ui/GrowthAlbumDateTest.kt` (신규) | 날짜 파서 단위 테스트 4건 |

## 새 디자인 (동작 흐름)

1. **헤더**: 📸 성장 사진첩 + `N장` 배지 + `전체 보기 ›`
2. **기간 요약**: `10.1 ~ 11.25 · 56일간의 기록` + `▶ 성장 재생` 버튼
3. **대표 사진(4:3)**: `HorizontalPager` — 스와이프/‹ › 버튼으로 이동, 처음엔 최신 사진.
   오버레이: 좌상단 `D+N`(심은 날 기준), 우상단 `n / 전체`, 하단 날짜·시각·📍위치 + 크게 보기 아이콘.
   썸네일(480px)을 먼저 깔고 원본이 로드되면 덮음.
4. **썸네일 띠**: `LazyRow` 54dp — 탭하면 해당 사진으로 이동, 현재 사진을 따라 자동 스크롤,
   날짜가 바뀌는 첫 사진에만 `M.d` 라벨.
5. **성장 재생**: 처음부터 끝까지 자동 넘김(플립북). 전체 ≈8초가 되도록 간격 180~700ms 자동 조절,
   손으로 넘기면 정지.
6. **전체 보기**: 전체 화면 3열 그리드, 날짜별 그룹(최신 날짜 위) + 그룹별 D+N·장수. 탭 → 뷰어.
7. **전체 화면 뷰어**: 좌우 스와이프, 핀치/두 번 탭 확대(최대 5배, 확대 중엔 페이저 스와이프 잠금),
   한 번 탭으로 정보 숨김, 하단 썸네일 띠로 점프. 닫으면 보던 사진이 대표 사진으로 이어짐.
8. **빈 상태**: 🌱 + 안내 문구 카드.

- 날짜는 Farmer `yyyy-MM-dd HH:mm`, 서버 `yyyy-MM-dd HH:mm:ss`/ISO, `planted_at` 날짜만 모두 관대하게 파싱.
  포맷 패턴은 strings.xml(ko: `M월 d일 (E)`, en: `EEE, MMM d`)로 언어별 분리.

## 검증 결과

- `node scripts/verify-locales.mjs` ✅ (ko/en 키 일치)
- `:master:app:compileDebugKotlin` ✅ (신규 파일 경고 없음)
- `:master:app:testDebugUnitTest` ✅ 9/9 (AppLocale 3, ServerEndpoint 2, GrowthAlbumDate 4)
- `./scripts/build-dist.sh` ✅ → `dist/oojoo-farm-master-debug.apk` (19,068,540 bytes)
- 실기기/에뮬레이터 화면 확인은 **미실시** (이 PC에 에뮬레이터 없음)

## 빌드 환경 메모 (이 PC — `C:\Users\samsung`)

- 기존 빌드 도구가 없어 포터블로 구성: `%USERPROFILE%\tools\jdk-17.0.21.1+2` (Microsoft OpenJDK 17),
  `%USERPROFILE%\tools\android-sdk` (cmdline-tools, platform 34, build-tools 34.0.0 — AGP가 자동 설치).
  시스템 PATH/환경변수는 변경하지 않음. `android/local.properties`(gitignore)에 `sdk.dir` 지정.
- 빌드 명령: `JAVA_HOME=C:/Users/samsung/tools/jdk-17.0.21.1+2 ANDROID_HOME=C:/Users/samsung/tools/android-sdk ./scripts/build-dist.sh`
- Git Bash는 `TZ=Asia/Seoul`을 인식 못함(UTC로 계산) — KST 오전 9시 이전 빌드 시 태그 날짜가 하루 밀릴 수 있음.
- **서명 키 변경 주의**: 이 PC의 debug keystore가 새로 생성되어 master APK 서명 인증서가 바뀜
  (기존 `a87301b0…` → 신규 `d0a5726c…`). 기존 설치본 위에 업데이트 설치 불가 → **삭제 후 재설치** 필요
  (로컬 설정 초기화). 과거에도 머신 간 키가 바뀐 이력 있음(`fe6b7ecc…` → `a87301b0…`).
- farmer 코드는 변경이 없어 farmer APK는 재빌드본을 커밋하지 않고 기존(2026112506) 유지 — 불필요한 재설치 방지.

## 커밋

- `8541748 feat(master): growth album redesign — hero pager, thumbnail strip, viewer`
- `7bfd82e build: debug APKs 2026112601 with growth album redesign`

## 남은 작업

- 실기기에서 사진 수십~수백 장 식물로 스와이프/재생/확대 UX 확인
- 머신 간 서명 키 고정: 공용 debug keystore를 저장소(또는 안전한 위치)에 두고 `signingConfigs`로 지정할지 결정
- 사진이 매우 많아질 경우 `/api/photos/plant/:id` 페이지네이션 검토 (현재 전체 반환)
