---
type: Playbook
title: "GCP 백엔드 배포: main을 VM에 반영"
description: 푸시된 main을 oojoo-backend VM에 pull하고 서비스를 재시작한 뒤 확인하는 절차.
tags: [backend, gcp, deploy]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T04:58:00Z" }
sources:
  - id: deploy-skill
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/.opencode/skills/deploy-backend-gcp/SKILL.md
    title: deploy-backend-gcp 스킬
  - id: diary-2026-11-27
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/working_diary/2026-11-27_community-post-photo-video_01.md
    title: 2026-11-27 이웃 글 사진·동영상 첨부 일지
  - id: session-2026-11-27
    resource: "Claude Code session on the Windows dev PC, 2026-11-27 (명령 실행 결과로 확인)"
    title: 2026-11-27 세션 관찰
---

# Trigger

백엔드 변경이 main에 push된 뒤 "GCP 재배포" 요청을 받았을 때. 대상 서버는 [GCP 백엔드 VM](../infra/gcp-backend-vm.md)이다.

# Steps

1. 로컬 커밋을 먼저 push한다. VM은 GitHub에서 pull한다.[^deploy-skill]
2. 이 PC의 Git Bash에서 실행한다.

   ```bash
   gcloud compute ssh oojoo-backend --zone=asia-northeast3-a --quiet \
     --command="cd /opt/oojoo/repo && sudo -u wooju-family git pull --ff-only && cd backend && sudo -u wooju-family npm install --no-audit --no-fund && sudo systemctl restart oojoo && systemctl is-active oojoo" < /dev/null
   ```

   - repo 소유자가 `wooju-family` 라서 그냥 `git pull` 하면 dubious ownership/권한 오류가 난다.[^deploy-skill]
   - stdin을 `/dev/null` 로 닫아 ssh가 입력을 기다리며 멈추지 않게 한다.[^session-2026-11-27]
3. `active` 와 이전→새 커밋 해시를 확인한다.
4. `/health` 를 확인하고, 바뀐 API는 운영 DB에 흔적이 남지 않는 요청으로 확인한다(잘못된 id → 400, 없는 파일 → 404 등).[^diary-2026-11-27]
5. 일지와 커밋(`docs: GCP redeploy of <hash> …`)에 배포 결과를 남긴다.[^diary-2026-11-27]

[^deploy-skill]: deploy-backend-gcp 스킬
[^diary-2026-11-27]: 2026-11-27 이웃 글 사진·동영상 첨부 일지
[^session-2026-11-27]: 2026-11-27 세션 관찰
