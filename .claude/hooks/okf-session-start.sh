#!/usr/bin/env bash
# Claude Code SessionStart hook: show the OKF knowledge bundle catalog (index files only) so Claude knows what exists.
cat >/dev/null
root="${CLAUDE_PROJECT_DIR:-.}/.claude/knowledge"
[ -f "$root/index.md" ] || exit 0

{
  echo "[프로젝트 지식 번들] .claude/knowledge/ (OKF v0.3). 작업과 관련된 개념이 있으면 아래 목록에서 필요한 파일만 읽어 활용하세요."
  echo "턴이 끝나면 Stop 훅이 번들 갱신 여부를 묻습니다. 규칙: .claude/okf/GUIDE.md"
  echo
  echo "## .claude/knowledge/index.md"
  awk 'NR==1 && $0=="---"{fm=1; next} fm && $0=="---"{fm=0; next} !fm' "$root/index.md"
  for idx in "$root"/*/index.md; do
    [ -f "$idx" ] || continue
    rel=${idx#"$root"/}
    echo
    echo "## .claude/knowledge/$rel"
    cat "$idx"
  done
} | head -c 8000
exit 0
