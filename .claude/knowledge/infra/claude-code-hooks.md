---
type: Tooling
title: Claude Code 턴 종료 훅 (자동 커밋 + OKF)
description: Claude Code가 턴마다 .claude/knowledge 갱신을 점검하고 변경을 자동 커밋하는 프로젝트 훅 구성.
tags: [claude-code, git, okf]
generated: { by: "claude-code/claude-opus-5-5", at: "2026-11-27T05:44:45Z" }
sources:
  - id: claude-dir
    resource: https://github.com/chungbrain/OOJOO-FARM/tree/main/.claude
    title: .claude 폴더 (설정·훅·OKF 도구)
  - id: session-2026-11-27
    resource: "Claude Code session on the Windows dev PC, 2026-11-27 (명령 실행 결과로 확인)"
    title: 2026-11-27 세션 관찰
  - id: okf-spec
    resource: https://github.com/open-knowledge-format/open-knowledge-format/blob/main/SPEC.md
    title: OKF v0.3 스펙
---

훅은 저장소에 커밋된 `.claude/settings.json` 에 있다. 그래서 이 저장소를 Claude Code로 여는 모든 PC에 적용된다. 2026-11-27 처음에는 이 PC 전용(`settings.local.json`, gitignore)이었다가 같은 날 저장소로 옮겼다.[^claude-dir]

| 이벤트 | 스크립트 | 하는 일 |
|---|---|---|
| SessionStart | `.claude/hooks/okf-session-start.sh` | `.claude/knowledge/` 의 index 목록을 세션 컨텍스트에 넣음 |
| Stop (턴의 첫 종료) | `.claude/hooks/turn-end.sh` | 한 번 막고 번들 갱신 점검을 요청 |
| Stop (`stop_hook_active`) | `.claude/hooks/auto-commit.sh` | `git add -A` 후 `chore(auto): end-of-turn commit - <영역> (<시각>)` 커밋 |

자동 커밋은 push하지 않는다. merge·rebase 중이거나 detached HEAD면 건너뛰고, `.env`·`.claude/settings.local.json`·50MB 초과 파일은 제외하며, git 사용자가 없으면 `chungbrain` noreply로 커밋한다.[^claude-dir] 훅은 bash로 실행되므로 Windows에서는 Git Bash가 필요하다.

번들 규칙은 `.claude/okf/GUIDE.md`, 검증은 `bash .claude/okf/validate.sh` 로 한다. 검증기는 OKF 저장소의 `validate.py` 사본이고 PyYAML이 필요하다. `.claude/okf/.venv` 는 gitignore라서 새 클론마다 한 번 만들어야 한다(명령은 `.claude/README.md`).[^claude-dir] 번들 형식은 OKF v0.3을 따른다.[^okf-spec]

훅을 보거나 끄려면 `/hooks` 를 연다. 한 PC에서만 끄려면 `.claude/settings.local.json` 에 `{ "disableAllHooks": true }` 를 둔다.

# 함정

- Stop 훅이 막은 뒤 빈 응답으로 끝내면 Claude Code가 "보이는 응답"을 다시 요구한다. 그래서 기록할 게 없을 때도 한 줄(「기록할 새 지식 없음」)로 답하게 했다.[^session-2026-11-27]
- 훅 메시지(reason)에 큰따옴표를 넣으면 훅이 출력하는 JSON이 깨진다. 따옴표 대신 「」를 쓴다.[^session-2026-11-27]

[^claude-dir]: .claude 폴더 (설정·훅·OKF 도구)
[^session-2026-11-27]: 2026-11-27 세션 관찰
[^okf-spec]: OKF v0.3 스펙
