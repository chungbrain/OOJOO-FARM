#!/usr/bin/env bash
# Claude Code Stop hook for OOJOO-FARM.
# 1st stop of a turn  -> block once and ask Claude to update the OKF bundle (.claude/knowledge/) if the turn taught something durable.
# 2nd stop (stop_hook_active=true) -> run auto-commit.sh, so code and knowledge land in one commit.

input=$(cat)
here=$(cd "$(dirname "$0")" && pwd)

if printf '%s' "$input" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  printf '%s' "$input" | bash "$here/auto-commit.sh"
  exit 0
fi

reason='[OKF 턴 종료 점검] 이번 턴에서 다음 세션에도 쓸 만한 지식(환경·경로 특이점, 통한 절차, 인프라 사실, 설계 결정과 이유, 기능 계약, 함정과 해결, 낡은 지식 정정)이 생겼으면 .claude/knowledge/ OKF 번들에 반영하세요. 규칙: .claude/okf/GUIDE.md (처음이면 읽기). 기존 개념 갱신 우선, 코드·README·일지에 이미 있는 내용·사소한 것·비밀·개인정보는 제외. 바꿨다면 해당 index.md와 log.md를 갱신하고 bash .claude/okf/validate.sh 로 검증한 뒤 바꾼 개념을 한 줄로 알려 주세요. 기록할 게 없으면 도구를 쓰지 말고 「기록할 새 지식 없음」 한 줄만 답하고 끝내세요(빈 응답은 Claude Code가 다시 요구함).'

printf '{"decision":"block","reason":"%s"}\n' "$reason"
exit 0
