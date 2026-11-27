#!/usr/bin/env bash
# Validate the project knowledge bundle (.claude/knowledge/) against OKF v0.3.
# validate.py is a copy of skills/okf-generate/scripts/validate.py from
# github.com/open-knowledge-format/open-knowledge-format (Apache-2.0, commit 527e81a; see LICENSE-okf.txt).
# Usage: bash .claude/okf/validate.sh [--json] [--strict]
# Needs PyYAML. One-time setup per clone (the venv is gitignored):
#   python -m venv .claude/okf/.venv && .claude/okf/.venv/Scripts/python -m pip install pyyaml   (Windows)
#   python3 -m venv .claude/okf/.venv && .claude/okf/.venv/bin/python -m pip install pyyaml      (macOS/Linux)
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
py="$here/.venv/Scripts/python.exe"
[ -x "$py" ] || py="$here/.venv/bin/python"
if [ ! -x "$py" ]; then
  for cand in python3 python; do
    if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import yaml' >/dev/null 2>&1; then py=$cand; break; fi
  done
fi
if ! command -v "$py" >/dev/null 2>&1 && [ ! -x "$py" ]; then
  echo "PyYAML이 있는 Python을 찾지 못했습니다. 한 번만 설정하세요:" >&2
  sed -n '7,8p' "$0" | sed 's/^#  */  /' >&2
  exit 2
fi
exec "$py" -I "$here/validate.py" "$root/.claude/knowledge" "$@"
