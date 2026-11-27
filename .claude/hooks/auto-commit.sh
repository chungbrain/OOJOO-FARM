#!/usr/bin/env bash
# Claude Code Stop hook: commit every change in the repo at the end of each turn.
# - Commits only; never pushes.
# - Skips when nothing changed, HEAD is detached, or a merge/rebase/cherry-pick is in progress.
# - Never stages .env files or .claude/settings.local.json; holds back files over 50 MB.
# - Prints one {"systemMessage": ...} line so the result shows in the Claude Code UI.

cat >/dev/null   # hook input JSON on stdin is not needed

say() { printf '{"systemMessage":"auto-commit: %s"}\n' "$1"; }

cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0

gitdir=$(git rev-parse --git-dir)
for f in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD REBASE_HEAD BISECT_LOG rebase-merge rebase-apply; do
  if [ -e "$gitdir/$f" ]; then say "skipped (git operation in progress: $f)"; exit 0; fi
done
git symbolic-ref -q HEAD >/dev/null || { say "skipped (detached HEAD)"; exit 0; }

[ -n "$(git status --porcelain 2>/dev/null)" ] || exit 0

git add -A >/dev/null 2>&1 || { say "skipped (git add failed - index.lock?)"; exit 0; }
git reset -q -- ':(glob)**/.env' ':(glob)**/*.env' '.claude/settings.local.json' >/dev/null 2>&1

held=""
while IFS= read -r -d '' f; do
  [ -f "$f" ] || continue
  size=$(wc -c <"$f" 2>/dev/null | tr -d ' ')
  if [ "${size:-0}" -gt 52428800 ]; then
    git reset -q -- "$f" >/dev/null 2>&1
    held="$held $(basename "$f")"
  fi
done < <(git diff --cached --name-only -z --diff-filter=AM)

if git diff --cached --quiet; then
  [ -n "$held" ] && say "nothing committed; held back files over 50 MB:$held"
  exit 0
fi

count=$(git diff --cached --name-only | wc -l | tr -d ' ')
areas=$(git -c core.quotepath=false diff --cached --name-only | awk -F/ '{print (NF>1?$1:"root")}' | sort -u | head -4 | paste -sd, - | sed 's/,/, /g')
stamp=$(date '+%Y-%m-%d %H:%M')

msgfile=$(mktemp)
{
  echo "chore(auto): end-of-turn commit - $areas ($stamp)"
  echo
  echo "Automatic commit by the Claude Code Stop hook ($count files)."
  echo
  git -c core.quotepath=false diff --cached --name-status | head -40
  [ "$count" -gt 40 ] && echo "... and $((count - 40)) more"
  echo
  echo "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
} >"$msgfile"

ident=()
git config user.name  >/dev/null || ident+=(-c user.name=chungbrain)
git config user.email >/dev/null || ident+=(-c user.email=chungbrain@users.noreply.github.com)

if out=$(git "${ident[@]}" commit -q -F "$msgfile" 2>&1); then
  hash=$(git rev-parse --short HEAD)
  msg="$hash ($count files: $areas)"
  [ -n "$held" ] && msg="$msg; held back over 50 MB:$held"
  say "$msg"
else
  say "commit failed: $(printf '%s' "$out" | head -1 | tr -d '"\\')"
fi
rm -f "$msgfile"
exit 0
