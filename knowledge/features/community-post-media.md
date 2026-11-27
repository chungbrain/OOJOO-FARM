---
type: Feature
title: 이웃 글 사진·동영상 첨부
description: 이웃 글에 사진·동영상을 최대 10개 첨부하는 업로드·저장·표시 계약과 설계 이유.
tags: [community, media, backend, android]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T04:58:00Z" }
sources:
  - id: diary-2026-11-27
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/working_diary/2026-11-27_community-post-photo-video_01.md
    title: 2026-11-27 이웃 글 사진·동영상 첨부 일지
  - id: media-lib
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/backend/src/lib/communityMedia.js
    title: backend/src/lib/communityMedia.js
---

2026-11-27에 추가됐다(backend `d158140`, master `90d28ec`, APK 2026112701, VM 배포 `305c6f1`). 설계 표와 변경 파일 목록은 일지에 있다.[^diary-2026-11-27]

# 계약

| 항목 | 값 |
|---|---|
| 개수 | 글당 최대 10개 |
| 사진 | jpeg/png/webp ≤ 20MB. 앱이 긴 변 1920px JPEG로 다시 인코딩한다(EXIF 회전은 반영하고 나머지 EXIF는 버림) |
| 동영상 | mp4/3gp/webm/mov/mkv ≤ 100MB. 원본 그대로 올린다 |
| 썸네일 | ≤ 2MB (앱이 480px JPEG로 만듦) |
| 형식 판정 | MIME 기준. 원본 파일명은 무시한다 |

한도와 허용 형식은 `communityMedia.js` 에 있다.[^media-lib] API는 `/api/community` 아래에 있다. `POST /media` 로 업로드하고, `GET /media/file/:filename` 은 Range(206)를 지원하며, `DELETE /media/:id` 는 본인 소유이면서 글에 붙지 않은 첨부만 지운다. `POST /posts` 의 `mediaIds` 순서대로 첨부되고, 모든 글 응답에 `media` 배열이 들어간다.[^diary-2026-11-27]

# 설계 결정

- 업로드를 먼저 하고 글을 등록할 때 `mediaIds` 로 붙인다. 글에 안 붙은 업로드는 24시간 뒤 `sweepOrphanMedia` 가 지운다(서버가 1시간마다 실행).[^diary-2026-11-27]
- 앱 업로드는 `ApiClient` 와 다른 OkHttp 클라이언트로 한다. BODY 로깅 인터셉터가 요청 본문을 통째로 버퍼링해서 100MB 동영상이면 메모리가 터지기 때문이다.[^diary-2026-11-27]
- 파일은 `backend/data/community/` (`COMMUNITY_MEDIA_DIR` 로 변경 가능, gitignore), 메타데이터는 `community_media` 테이블에 둔다.[^media-lib]

# 남은 일

실기기 확인을 아직 안 했다. 글 수정·삭제, 카메라로 바로 촬영해 첨부하기가 없다. Android 10 미만에서는 동영상 메타데이터가 남을 수 있다.[^diary-2026-11-27] [VM 디스크](../infra/gcp-backend-vm.md) 보관 정책도 정해야 한다.

[^diary-2026-11-27]: 2026-11-27 이웃 글 사진·동영상 첨부 일지
[^media-lib]: backend/src/lib/communityMedia.js
