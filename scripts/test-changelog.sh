#!/usr/bin/env bash
# The git-cliff template is plain text: build number, date, groups, "- " entries.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

cd "$WORKDIR"
git init -q
git config user.name "Test"
git config user.email "test@example.com"

export GIT_AUTHOR_DATE="2026-09-01T12:00:00Z"
export GIT_COMMITTER_DATE="2026-09-01T12:00:00Z"
git commit -q --allow-empty -m "$(printf 'fix(sms): correct the seal\n\nRefs: #1\n')"
git tag -a "2026-09_r40" -m "2026-09_r40"
git tag -a "sms/2026-09_r1" -m "not a portal release"

export GIT_AUTHOR_DATE="2026-10-03T15:04:05Z"
export GIT_COMMITTER_DATE="2026-10-03T15:04:05Z"
git commit -q --allow-empty -m "$(printf 'feat(dashboard)!: add the pump switch\n\nBREAKING CHANGE: the switch id changed\n\nRefs: #2\n')"
git commit -q --allow-empty -m "$(printf 'fix(mqtt): stop the empty reading\n\nRefs: #3\n')"
git commit -q --allow-empty -m "Merge pull request #9 from someone/branch"
git commit -q --allow-empty -m "Show the badge on the header"
git tag -a "1.1.0" -m "2026-10_r60"

CHANGELOG_REPO="$WORKDIR" CHANGELOG_OUT="${WORKDIR}/CHANGELOG.txt" \
  "${ROOT}/scripts/render-changelog.sh"

cat > "${WORKDIR}/expected.txt" <<'EOF'
2026-10_r60 (2026-10-03)
Breaking changes
- add the pump switch (dashboard)
Fixes
- stop the empty reading (mqtt)
Other
- Show the badge on the header

2026-09_r40 (2026-09-01)
Fixes
- correct the seal (sms)
EOF

if ! cmp -s "${WORKDIR}/CHANGELOG.txt" "${WORKDIR}/expected.txt"; then
  echo "changelog template mismatch" >&2
  diff -u "${WORKDIR}/expected.txt" "${WORKDIR}/CHANGELOG.txt" >&2 || true
  exit 1
fi

if grep -E '^(#|[*`])|\*\*|\]\(' "${WORKDIR}/CHANGELOG.txt"; then
  echo "changelog contains markdown" >&2
  exit 1
fi

latest="$("${ROOT}/.tools/bin/git-cliff" --config "${ROOT}/cliff.toml" -r "$WORKDIR" --latest)"
latest="$(printf '%s\n' "$latest" | python3 -c 'import sys; print(sys.stdin.read().strip())')"
if [[ "$latest" != 2026-10_r60\ \(2026-10-03\)* ]]; then
  echo "latest release notes do not start with the build number" >&2
  printf '%s\n' "$latest" >&2
  exit 1
fi
if grep -q "2026-09_r40" <<< "$latest"; then
  echo "latest release notes include an older release" >&2
  exit 1
fi

echo "changelog ok"
