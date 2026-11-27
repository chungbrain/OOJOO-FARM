---
type: Playbook
title: "커밋·푸시·작업 일지: 저장소 관례"
description: 논리 단위 분할 커밋, 메시지 접두사, working_diary, 문서 검사 등 작업 마무리 관례.
tags: [git, docs, convention]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T04:58:00Z" }
sources:
  - id: commit-skill
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/.claude/skills/commit-push-diary/SKILL.md
    title: commit-push-diary 스킬
  - id: diary-2026-11-27
    resource: https://github.com/chungbrain/OOJOO-FARM/blob/main/working_diary/2026-11-27_community-post-photo-video_01.md
    title: 2026-11-27 이웃 글 사진·동영상 첨부 일지
  - id: session-2026-11-27
    resource: "Claude Code session on the Windows dev PC, 2026-11-27 (명령 실행 결과로 확인)"
    title: 2026-11-27 세션 관찰
---

# Trigger

기능 작업을 마치고 커밋·푸시하거나 사용자가 "커밋해", "푸시해"라고 할 때. 이때는 일지 작성까지 포함한다.[^commit-skill]

# Steps

1. 접두사 `feat:`, `fix:`, `docs:`, `build:`, `chore:` 와 영어 요약을 쓰고, 논리 단위로 나눠 커밋한다.[^commit-skill] 기능 하나는 보통 `feat(backend)` → `feat(master)` → `build: debug APKs …` → `docs: …` 순서다.[^diary-2026-11-27]
2. 이 PC에는 git 사용자가 설정돼 있지 않다. 전역 설정은 바꾸지 말고 커밋마다 지정한다.[^diary-2026-11-27]
   `git -c user.name=chungbrain -c user.email=chungbrain@users.noreply.github.com commit -F <메시지 파일>`
3. 코드 구조가 바뀌면 `node scripts/code-graph.mjs` 로 `docs/code-graph/generated*.md` 를 다시 만들고 `--check` 로 확인한다. 문자열을 추가하면 `node scripts/verify-locales.mjs` 를 돌린다(`values/` 와 `values-en/` 쌍, 영어 아포스트로피는 `\'`).[^session-2026-11-27]
4. 일지는 `working_diary/{YYYY-MM-DD}_{제목}_{nn}.md` 에 한국어로 쓴다. 요구사항, 설계, 변경 파일 표, 흐름, 검증 결과, 남은 작업을 담는다. 규칙 원문은 `working_diary/README.md` 다.[^commit-skill]
5. push는 `git push origin main` 이다.[^commit-skill] 턴이 끝날 때 하는 [자동 커밋](../infra/claude-code-hooks.md)은 push하지 않는다.

[^commit-skill]: commit-push-diary 스킬
[^diary-2026-11-27]: 2026-11-27 이웃 글 사진·동영상 첨부 일지
[^session-2026-11-27]: 2026-11-27 세션 관찰
