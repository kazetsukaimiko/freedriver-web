#!/usr/bin/env bash
# commitlint accepts the squash message and rejects a bad title or footer.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"${ROOT}/scripts/install-commitlint.sh"
BIN="${ROOT}/.tools/commitlint/node_modules/.bin/commitlint"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

check() {
  local expect="$1"
  local title="$2"
  local body="$3"
  local file="${WORKDIR}/msg.txt"
  PR_TITLE="$title" PR_BODY="$body" python3 "${ROOT}/scripts/pr-commit-message.py" > "$file"
  set +e
  "$BIN" --config "${ROOT}/commitlint.config.js" --edit "$file" >"${WORKDIR}/out.txt" 2>&1
  local rc=$?
  set -e
  if [[ "$expect" == pass && "$rc" -ne 0 ]]; then
    echo "expected pass: ${title}" >&2
    cat "${WORKDIR}/out.txt" >&2
    exit 1
  fi
  if [[ "$expect" == fail && "$rc" -eq 0 ]]; then
    echo "expected fail: ${title}" >&2
    exit 1
  fi
}

check pass "ci(ci): enforce conventional commits" $'Generate the changelog in CI.\n\nRefs: #151'

check pass "feat(dashboard)!: change the switch" $'Ids move.\n\nBREAKING CHANGE: the switch id changed\nRefs: #151'

check fail "ci: missing scope" $'Refs: #151'
check fail "style(ci): not a type" $'Refs: #151'
check fail "ci(nope): unknown scope" $'Refs: #151'
check fail "ci(ci): missing the card" "Generate the changelog."
check fail "feat(dashboard)!: missing the footer" $'Ids move.\n\nRefs: #151'
check fail "feat(dashboard): footer without bang" $'BREAKING CHANGE: the switch id changed\n\nRefs: #151'

if PR_TITLE="" PR_BODY="Refs: #151" python3 "${ROOT}/scripts/pr-commit-message.py" >/dev/null 2>&1; then
  echo "empty title must fail" >&2
  exit 1
fi

echo "commitlint ok"
