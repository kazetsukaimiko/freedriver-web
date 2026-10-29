#!/usr/bin/env bash
# stamp-release-pr.py turns a semver changelog heading into YEAR-MONTH_rN.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${ROOT}/scripts/stamp-release-pr.py"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

fail() {
  echo "$1" >&2
  exit 1
}

changelog_1() {
  cat >"$1" <<'EOF'
# Changelog

## 1.0.0 (2026-10-29)

### Features

* something
EOF
}

got="$(python3 "$SCRIPT" --changelog /dev/null --version-file "$WORKDIR/missing.txt" --today 2026-10 --known-stamp 2026-10_r140)"
[[ "$got" == "2026-10_r141" ]] || fail "expected r141, got ${got}"

got="$(python3 "$SCRIPT" --changelog /dev/null --version-file "$WORKDIR/missing.txt" --today 2026-10 --known-stamp 2026-09_r12)"
[[ "$got" == "2026-10_r1" ]] || fail "expected r1 in new month, got ${got}"

changelog_1 "$WORKDIR/CHANGELOG.md"
got="$(python3 "$SCRIPT" --changelog "$WORKDIR/CHANGELOG.md" --version-file "$WORKDIR/version.txt" --today 2026-10 --known-stamp 2026-10_r140 --write)"
[[ "$got" == "2026-10_r141" ]] || fail "write returned ${got}"
[[ "$(cat "$WORKDIR/version.txt")" == "2026-10_r141" ]] || fail "version.txt"
grep -qx '## 2026-10_r141 (2026-10-29)' "$WORKDIR/CHANGELOG.md" || fail "heading"
grep -q '## 1.0.0' "$WORKDIR/CHANGELOG.md" && fail "semver heading left behind"

got="$(python3 "$SCRIPT" --changelog "$WORKDIR/CHANGELOG.md" --version-file "$WORKDIR/version.txt" --today 2026-10 --known-stamp 2026-10_r140 --write)"
[[ "$got" == "2026-10_r141" ]] || fail "keep existing heading ${got}"
[[ "$(grep -c '## 2026-10_r141' "$WORKDIR/CHANGELOG.md")" -eq 1 ]] || fail "duplicate heading"

echo "stamp-release-pr: ok"
