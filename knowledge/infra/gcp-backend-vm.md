---
type: Server
title: GCP 백엔드 VM (oojoo-backend)
description: 운영 백엔드가 도는 GCP e2-small VM의 위치, 소스 경로, 서비스, 저장 공간 상태.
resource: https://console.cloud.google.com/compute/instancesDetail/zones/asia-northeast3-a/instances/oojoo-backend?project=oojoo-farm
tags: [backend, gcp, infra]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T04:58:00Z" }
sources:
  - id: deploy-skill
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/.opencode/skills/deploy-backend-gcp/SKILL.md
    title: deploy-backend-gcp 스킬
  - id: diary-2026-11-27
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/working_diary/2026-11-27_community-post-photo-video_01.md
    title: 2026-11-27 이웃 글 사진·동영상 첨부 일지
---

운영 백엔드(Express + `node:sqlite`, 포트 4000)가 도는 VM이다. GCP 프로젝트 `oojoo-farm`, 존 `asia-northeast3-a`(서울), e2-small, 고정 IP `34.50.40.104`, 외부 주소 `http://34.50.40.104:4000/`.[^deploy-skill]

| 항목 | 값 |
|---|---|
| 소스 | `/opt/oojoo/repo` (GitHub clone, 소유자 `wooju-family`) |
| 서비스 | systemd `oojoo.service` (자동 재시작) |
| 모델 업로드 토큰 | systemd drop-in `/etc/systemd/system/oojoo.service.d/model-manager.conf` 의 `MODEL_MANAGER_TOKEN` (값은 기록하지 않음) |
| 배포 후 확인 API | `/health`, `/api/detectors`, `/api/models` |

위 표는 deploy-backend-gcp 스킬 기준이다.[^deploy-skill] 이웃 글 첨부 파일은 `backend/data/community/` 에 쌓인다.[^diary-2026-11-27]

# 저장 공간 (2026-11-27 기준)

디스크 8.7G 중 2.4G 남음(사용 73%), `backend/data` 551M.[^diary-2026-11-27] [이웃 글 동영상](../features/community-post-media.md)은 개당 최대 100MB라 쌓이면 디스크가 먼저 찬다. 상한·보관 정책은 아직 정하지 않았다.

배포 절차는 [GCP 백엔드 배포](../playbooks/deploy-backend-gcp.md)를 본다.

[^deploy-skill]: deploy-backend-gcp 스킬
[^diary-2026-11-27]: 2026-11-27 이웃 글 사진·동영상 첨부 일지
