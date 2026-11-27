---
type: Tooling
title: Claude Code 턴 종료 훅 (자동 커밋 + OKF)
description: 이 PC의 Claude Code가 턴마다 knowledge/ 갱신을 점검하고 변경을 자동 커밋하는 로컬 훅 구성.
tags: [claude-code, git, okf]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T05:02:00Z" }
sources:
  - id: hook-setup
    resource: "Local files .claude/settings.local.json, .claude/hooks/, .claude/okf/ (gitignored, set up 2026-11-27)"
    title: 로컬 훅 설정
  - id: okf-spec
    resource: https://github.com/open-knowledge-format/open-knowledge-format/blob/main/SPEC.md
    title: OKF v0.3 스펙
---

모든 파일이 gitignore 대상이라 이 PC에만 있다. 다른 PC나 새 클론에는 없다.[^hook-setup]

| 이벤트 | 스크립트 | 하는 일 |
|---|---|---|
| SessionStart | `.claude/hooks/okf-session-start.sh` | `knowledge/` 의 index 목록을 세션 컨텍스트에 넣음 |
| Stop (턴의 첫 종료) | `.claude/hooks/turn-end.sh` | 한 번 막고 knowledge/ 갱신 점검을 요청 |
| Stop (`stop_hook_active`) | `.claude/hooks/auto-commit.sh` | `git add -A` 후 `chore(auto): end-of-turn commit - <영역> (<시각>)` 커밋 |

자동 커밋은 push하지 않는다. merge·rebase 중이거나 detached HEAD면 건너뛰고, `.env`·`.claude/settings.local.json`·50MB 초과 파일은 제외하며, git 사용자가 없으면 `chungbrain` noreply로 커밋한다.[^hook-setup]

번들 규칙은 `.claude/okf/GUIDE.md`, 검증은 `bash .claude/okf/validate.sh` 로 한다. 이 스크립트는 OKF 저장소의 `validate.py` 사본을 `.claude/okf/.venv`(PyYAML)로 실행한다.[^hook-setup] 번들 형식은 OKF v0.3을 따른다.[^okf-spec]

훅을 보거나 끄려면 Claude Code에서 `/hooks` 를 연다.

# 함정

- Stop 훅이 막은 뒤 빈 응답으로 끝내면 Claude Code가 "보이는 응답"을 다시 요구한다. 그래서 기록할 게 없을 때도 한 줄("기록할 새 지식 없음")로 답하게 했다(2026-11-27 첫 실행에서 확인).[^hook-setup]

[^hook-setup]: 로컬 훅 설정
[^okf-spec]: OKF v0.3 스펙
