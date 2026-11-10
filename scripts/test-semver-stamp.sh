#!/usr/bin/env bash
# The live stamp is the semver in version.txt. release-please owns the bump.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "$1" >&2
  exit 1
}

version="$(tr -d '[:space:]' < "${ROOT}/version.txt")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version.txt must be semver X.Y.Z, got ${version}"

manifest="$(python3 - <<PY
import json
from pathlib import Path
print(json.loads(Path("${ROOT}/.release-please-manifest.json").read_text())["."])
PY
)"
[[ "$manifest" == "$version" ]] || fail "manifest ${manifest} must match version.txt ${version}"

deploy="${ROOT}/.github/workflows/deploy.yml"
grep -q 'Stamp semver from version.txt' "$deploy" || fail "deploy.yml stamps from version.txt"
grep -F 'version.txt' "$deploy" | grep -q NEW_VERSION || fail "deploy.yml reads version.txt"
grep -q 'tag="v${NEW_VERSION}"' "$deploy" || fail "deploy.yml tags vX.Y.Z"
if grep -Eq 'date -u \+%Y-%m|_r\$\{\{ github.run_number \}\}|month_r' "$deploy"; then
  fail "deploy.yml still mints YEAR-MONTH_rN"
fi

release="${ROOT}/.github/workflows/release-please.yml"
if grep -Fq 'stamp-release-pr.py' "$release"; then
  fail "release-please.yml still rewrites the Release pull request"
fi

[[ ! -e "${ROOT}/scripts/stamp-release-pr.py" ]] || fail "stamp-release-pr.py must be gone"
[[ ! -e "${ROOT}/scripts/test-stamp-release-pr.sh" ]] || fail "test-stamp-release-pr.sh must be gone"

config="${ROOT}/release-please-config.json"
grep -q 'chore(release): release \${version}' "$config" || fail "release-please title must stay chore(release)"
grep -q '"include-v-in-tag": true' "$config" || fail "release-please tags vX.Y.Z"

commitlint="${ROOT}/commitlint.config.js"
grep -Fq '[0-9]+\.[0-9]+\.[0-9]+$' "$commitlint" || fail "commitlint skip must be semver"

echo "semver-stamp: ok ${version}"
