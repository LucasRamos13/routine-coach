#!/usr/bin/env bash
# Blocks commits that contain secrets or personal data.
#   scripts/check-private-data.sh            check staged files (pre-commit hook)
#   scripts/check-private-data.sh --all      check every tracked file (CI)
# Extra personal patterns (your name, phone, body data...) can be listed one regex per line in
# .private-patterns at the repository root. That file is git-ignored and never leaves your machine.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if [[ "${1:-}" == "--all" ]]; then
  mapfile -t files < <(git ls-files)
else
  mapfile -t files < <(git diff --cached --name-only --diff-filter=ACMR)
fi
[[ ${#files[@]} -eq 0 ]] && exit 0

fail=0
report() { echo "✖ $1"; fail=1; }

# 1. Files that must never be committed.
for f in "${files[@]}"; do
  case "$f" in
    local.properties|*/local.properties) report "$f: local.properties holds machine paths and the private pack location" ;;
    *.env|*/.env|*.env.local) report "$f: environment files hold tokens" ;;
    *.rcbackup|*.db|*.db-wal|*.db-shm) report "$f: personal training data" ;;
    *.pdf|*.docx) report "$f: documents are kept out of the public repository" ;;
    private-pack/*|*/private-pack/*) report "$f: private content pack" ;;
    .private-patterns) report "$f: the personal pattern list itself" ;;
    *.jks|*.keystore) report "$f: signing key" ;;
  esac
done

# 2. Secret and personal-data patterns in file contents.
patterns=(
  '[0-9]{8,10}:AA[A-Za-z0-9_-]{33}'                 # Telegram bot token
  'gh[pousr]_[A-Za-z0-9]{36}'                        # GitHub tokens
  'github_pat_[A-Za-z0-9_]{40,}'
  'AKIA[0-9A-Z]{16}'                                 # AWS access key
  'tk_[a-z0-9]{29}'                                  # ntfy access token
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'RC_TELEGRAM_TOKEN=[^[:space:]]+'
  'RC_NTFY_TOPIC=[^r[:space:]][^[:space:]]*'         # a real topic (the example uses "replace-...")
)
email='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
email_ok='^(this|super|return|break|continue)@|noreply|no-reply|example\.(com|org)|users\.noreply\.github\.com'

if [[ -f .private-patterns ]]; then
  while IFS= read -r p; do [[ -n "$p" && "$p" != \#* ]] && patterns+=("$p"); done < .private-patterns
fi

for f in "${files[@]}"; do
  [[ -f "$f" ]] || continue
  [[ "$f" == "scripts/check-private-data.sh" ]] && continue
  if file -b --mime "$f" | grep -q 'charset=binary'; then continue; fi
  for p in "${patterns[@]}"; do
    if grep -nEi -- "$p" "$f" >/dev/null 2>&1; then
      report "$f: matches a forbidden pattern ($(grep -nEi -m1 -- "$p" "$f" | cut -d: -f1 | sed 's/^/line /'))"
    fi
  done
  while IFS= read -r m; do
    [[ -z "$m" ]] && continue
    echo "$m" | grep -Eqi "$email_ok" || report "$f: contains an e-mail address ($m)"
  done < <(grep -oE "$email" "$f" 2>/dev/null | sort -u)
done

if [[ $fail -ne 0 ]]; then
  echo
  echo "Commit blocked: remove the personal data or secrets above (see docs/privacy.md)."
  exit 1
fi
echo "✔ no secrets or personal data found in ${#files[@]} file(s)"
