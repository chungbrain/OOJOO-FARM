# 2026-11-27 이웃 글 사진·동영상 첨부 (community-post-photo-video)

## 요구사항

1. 이웃 탭 글쓰기에서 **수동으로 사진·동영상을 올릴 수 있게** 할 것.
2. **게시된 글에서도** 그 사진·동영상을 볼 수 있게 할 것.
3. 빌드 후 모두 push.

## 이전 상태

- 글쓰기 화면은 텍스트(제목·작물·수량·가격·설명)와 이모지 아이콘만 있었음. `image` 컬럼은 이모지 1개.
- 글에 붙는 영상은 식물 카드 "나눔/판매"에서 Farmer 가 만든 **성장 스토리 클립**(`video_id`)뿐 — 사용자가 직접 고른 파일은 올릴 방법이 없었음.
- master 앱에는 업로드 코드가 전혀 없었음 (사진·영상 업로드는 모두 Farmer 쪽 `slaveAuth` 경로).

## 설계

| 항목 | 결정 |
|---|---|
| 업로드 순서 | 파일을 하나씩 `POST /api/community/media` 로 먼저 올려 id 를 받고, 글 작성 `POST /posts` 에 `mediaIds` 로 붙임 → 글은 첨부와 함께 한 번에 생김(반쯤 빈 글 없음). 실패 시 올라간 것은 유지하고 남은 것만 재시도 |
| 저장 | 새 테이블 `community_media`(post_id 는 붙기 전 NULL, sort = 표시 순서), 파일 `backend/data/community/`(`COMMUNITY_MEDIA_DIR` 로 변경 가능, gitignore) |
| 검증 | 형식은 MIME 으로(확장자도 MIME 으로 정함 — 원본 파일명 무시). 사진 jpeg/png/webp ≤20MB, 동영상 mp4/3gp/webm/mov/mkv ≤100MB, 썸네일 ≤2MB(넘으면 버림). 413 크기 / 415 형식. 글당 최대 10개, 내 업로드·아직 안 붙은 것·중복 없음만 붙일 수 있음 |
| 서빙 | `GET /api/community/media/file/:name` — DB 에 등록된 원본·썸네일만, `res.sendFile` 로 Range(206)·ETag·캐시 |
| 정리 | 글에 안 붙은 업로드는 24시간 뒤 삭제(`sweepOrphanMedia`, server.js 에서 1시간마다). 작성 중 빼면 `DELETE /media/:id` 로 즉시 삭제 |
| 사진 처리(앱) | 긴 변 1920px, EXIF 방향 반영 후 JPEG 85 로 재인코딩 → 용량 절감 + 위치 등 EXIF 제거(PRD 6.7 정밀 좌표 비공개와 같은 방향). 480px 썸네일 함께 업로드 |
| 동영상 처리(앱) | 원본을 content:// 에서 바로 스트리밍(메모리에 안 올림). `MediaMetadataRetriever` 로 길이·표시 크기(회전 반영)·포스터 프레임(1초 지점) |
| 선택기 | `PickMultipleVisualMedia(10)` — 시스템 사진 선택기, 저장소 권한 불필요(구형 기기는 문서 선택기로 자동 대체) |
| 업로드 클라이언트 | `ApiClient` 의 OkHttp 는 BODY 로깅 인터셉터가 요청 본문을 통째로 버퍼링 → 100MB 동영상이면 메모리 폭발. 로깅 없는 별도 클라이언트(`MediaUploader`)로 스트리밍, 1% 단위 진행률, 코루틴 취소 시 요청도 취소 |

## 변경 내역

| 파일 | 내용 |
|---|---|
| `backend/src/lib/communityMedia.js` (신규) | 테이블·저장 경로·허용 형식/상한, `mediaForPosts`(글 목록 첨부를 쿼리 1번), `checkAttachable`/`attachMedia`, `sweepOrphanMedia`, `mimeForFilename` |
| `backend/src/routes/community.js` | `POST /media`(multer, 형식 필터·크기 초과 처리·실패 시 받은 파일 삭제), `GET /media/file/:name`, `DELETE /media/:id`, `POST /posts` 의 `mediaIds`, 모든 글 응답에 `media[]` |
| `backend/src/server.js` | 미첨부 업로드 정리 1시간 주기 |
| `backend/test/community_media.test.js` (신규) | 10건 — 업로드/서빙(바이트·Content-Type), Range 206, 거부(형식·파일 없음·사용자 없음·파일명 위장), 20MB 초과 413, mediaIds 순서·재사용 금지, 남의 것·없는 id·중복·11개·비배열, 기존 글 `media: []`, DELETE 권한·409, 경로 조작 404, 정리 |
| `backend/README.md` | 커뮤니티 API 표에 3개 엔드포인트·`mediaIds`·`media[]`, 테이블 목록 |
| `.gitignore` | `backend/data/community/` |
| `android/master/.../data/MediaPrep.kt` (신규) | `MediaRules`(순수 규칙: 형식 판정, 크기 상한, 샘플 크기, 축소 크기, 회전 표시 크기, 길이 표시, 파일명) + `MediaPrep`(썸네일·사진 재인코딩·EXIF 회전·동영상 메타데이터/포스터) |
| `android/master/.../network/MediaUploader.kt` (신규) | 멀티파트 업로드(스트리밍 본문, 진행률, 취소), `UploadException`(413/415 구분) |
| `android/master/.../ui/CommunityMedia.kt` (신규) | `DraftMediaSection`(추가 타일 + 항목 타일: 빼기·업로드 진행 원·완료 ✓·문제 ⚠️), `PostMediaGallery`(4:3 페이저 + 썸네일 줄 + 요약), 전체 화면 뷰어(사진 핀치 확대, 동영상 재생/일시정지/탐색 막대, 재생 실패 안내), `FeedMediaThumb` |
| `android/master/.../ui/CommunityScreen.kt` | 글쓰기 VM: 첨부 목록·준비·업로드(이어 올리기)·빼기(서버 삭제)·등록 후 **올린 글 상세로 바로 이동**. 피드: 카드 오른쪽 첫 첨부 썸네일(+N, ▶), **글 올리고 돌아오면 새로고침**(기존엔 새 글이 안 보였음). 상세: 맨 위 갤러리 |
| `android/master/.../ui/GrowthAlbum.kt` | `ZoomableImage` 를 URL 받는 `internal` 로 일반화 — 이웃 글 뷰어와 공용 |
| `android/master/.../model/Models.kt`, `network/ApiService.kt` | `CommunityMedia`, `CommunityPost.media`, `CreatePostRequest.mediaIds`, `communityMediaDelete` |
| `values/strings.xml`, `values-en/strings.xml` | 신규 21개 |
| `android/master/app/src/test/.../data/MediaRulesTest.kt` (신규) | 7건 |
| `README.md`, `README_kor.md`, `docs/code-graph/generated*.md` | What's New, 기능·API 설명, 빌드 태그, 코드 그래프 재생성 |

## 화면 흐름

1. 이웃 탭 → 글쓰기 → 설명 아래 **사진·동영상 (선택) n/10** 칸의 **추가** → 시스템 사진 선택기에서 여러 개 선택
2. 고른 항목이 84dp 타일로 바로 보임(동영상은 ▶ 길이). ✕ 로 빼기. 10개 넘게 고르면 앞에서부터 채우고 안내.
   100MB 넘는 동영상·지원 안 하는 형식은 ⚠️ 표시 → 빼야 등록 가능
3. **등록** → "올리는 중 2/4 · 45%" + 진행 막대, 타일마다 진행 원. 실패하면 "다시 누르면 이어서 올려요"
4. 등록 완료 → 올린 글 상세가 바로 열림: 맨 위 갤러리(좌우 넘김, 1/N, 썸네일 줄, 📷 n · 🎬 n)
5. 탭 → 전체 화면: 사진은 두 번 탭/핀치 확대, 동영상은 자동 재생, 탭으로 일시정지, 막대로 탐색
6. 뒤로 → 피드가 새로고침되어 새 글이 보이고, 카드 오른쪽에 첫 첨부 썸네일(+N)

## 검증 결과

- 백엔드 `node --test test/community_media.test.js` ✅ 10/10, 커뮤니티 관련 4파일 24/24
- 백엔드 전체 `npm test` 141/142 — 실패 1건(app.test.js SIGTERM graceful shutdown, `exit code null` = Windows 에서 신호로 즉시 종료)은 이전 일지들과 같은 Windows 환경 이슈
- `node scripts/verify-locales.mjs` ✅, `node scripts/code-graph.mjs --check` ✅ (재생성 후)
- `:master:app:testDebugUnitTest` ✅ 26/26 (MediaRules 7, 기존 19), 신규·수정 파일 컴파일 경고 없음
- `:master:app:assembleDebug` ✅ → `dist/oojoo-farm-master-debug.apk` (20,014,312 bytes, 빌드 2026112701). farmer 는 코드 변경이 없어 기존 APK(2026112604) 유지
  - `android/build.gradle.kts` 의 dist 자동 복사 훅이 이번에도 동작하지 않아 수동 복사 (build-apk-dist 스킬에 기록된 증상)
  - 서명 인증서 SHA-256 `d0a5726c…` — 이전 dist master APK 와 같음 (`apksigner verify --print-certs` 로 비교)
- **실기기·에뮬레이터 확인 미실시** — 이 PC 에 에뮬레이터 없음. 사진 선택기·EXIF 회전·동영상 포스터/재생·대용량 업로드 진행률의 실제 동작은 확인하지 못함. 서버 계약·순수 규칙·컴파일까지만 검증

## 배포 주의

- **GCP 백엔드(34.50.40.104) 재배포 전에는 첨부가 안 됨** — 구버전 서버는 `POST /api/community/media` 가 404 → 앱이 "업로드에 실패했어요" 안내. 첨부 없는 글쓰기는 구버전 서버에서도 그대로 동작(`mediaIds` 는 무시됨).
  재배포: VM 에서 `git pull` + `oojoo.service` 재시작 (`.opencode/skills/deploy-backend-gcp`). 새 의존성 없음(multer 기존 사용)
- 동영상 100MB × 여러 개가 쌓이면 e2-small VM 디스크를 차지함 — 사용량을 보고 상한/보관 정책 조정 필요
- master APK 서명 키는 이전 빌드와 같음(이 PC 키 d0a5726c) → 2026112601 이후 설치본 위에 업데이트 설치 가능

## 커밋

- `d158140 feat(backend): photo/video attachments for community posts`
- `90d28ec feat(master): attach photos and videos to Neighbor posts and view them in the post`
- `d70b700 build: debug APKs 2026112701 with Neighbor post photos and videos`
- `docs: README, API docs, code graph and working_diary for Neighbor post media (2026-11-27)`

## 환경 메모

- 이 PC 에는 git 사용자 정보가 설정돼 있지 않음 — 저장소 기존 작성자(`chungbrain <chungbrain@users.noreply.github.com>`)를
  커밋마다 `git -c user.name=… -c user.email=…` 로 지정 (전역 설정은 바꾸지 않음)

## 남은 작업

- 실기기에서 사진 선택기(Android 13+ / 구형 문서 선택기), 세로 사진 EXIF 회전, 동영상 포스터·재생·탐색, 큰 동영상 업로드 진행률 확인
- GCP 백엔드 재배포
- 동영상은 원본을 그대로 올림 — Android 10 미만 기기에서 고른 동영상은 메타데이터(촬영 위치 등)가 남을 수 있음(Android 10+ 는 MediaProvider 가 위치 정보를 가려서 줌). 필요하면 앱에서 재인코딩/메타데이터 제거 검토
- 글 수정·삭제 기능이 아직 없음 — 첨부도 글과 함께 지울 수 있게 하려면 글 삭제 API 부터 필요
- 카메라로 바로 촬영해 첨부하기(현재는 갤러리 선택만)
