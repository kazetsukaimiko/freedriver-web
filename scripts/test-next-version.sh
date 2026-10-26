#!/usr/bin/env bash
# Fixture repo for scripts/next-version.py. No network.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEXT="${ROOT}/scripts/next-version.py"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

cd "$WORKDIR"
git init -q
git config user.name "Test"
git config user.email "test@example.com"

expect() {
  local want="$1"
  local got
  got="$(python3 "$NEXT")"
  if [[ "$got" != "$want" ]]; then
    echo "next version: got ${got}, want ${want}" >&2
    exit 1
  fi
}

git commit -q --allow-empty -m "init"
expect "1.0.0"

git tag -a "2026-10_r129" -m "2026-10_r129"
expect "1.0.0"

git commit -q --allow-empty -m "chore(ci): refresh the cache"
git tag -a "1.0.0" -m "2026-10_r130"
if python3 "$NEXT" >/dev/null 2>"${WORKDIR}/already.err"; then
  echo "already-tagged HEAD must fail" >&2
  exit 1
fi
grep -q "HEAD is already 1.0.0" "${WORKDIR}/already.err"

git commit -q --allow-empty -m "fix(sms): correct the seal"
expect "1.0.1"
git tag -a "1.0.1" -m "2026-10_r131"

git commit -q --allow-empty -m "feat(dashboard): add the pump"
expect "1.1.0"
git commit -q --allow-empty -m "fix(mqtt): stop the empty reading"
expect "1.1.0"
git tag -a "1.1.0" -m "2026-10_r132"

git commit -q --allow-empty -m "$(printf 'feat(dashboard)!: change the switch\n\nBREAKING CHANGE: ids changed\n')"
expect "2.0.0"
git tag -a "2.0.0" -m "2026-10_r133"

git commit -q --allow-empty -m "$(printf 'fix(keycloak): drop the old step\n\nBREAKING CHANGE: the step id changed\n')"
expect "3.0.0"
git tag -a "3.0.0" -m "2026-10_r134"

git commit -q --allow-empty -m "refactor(webui): rename the badge"
expect "3.0.1"
# 1.10.0 is newer than 1.9.0 and older than 3.0.0. The max tag stays 3.0.0.
git tag -a "1.9.0" -m "older"
git tag -a "1.10.0" -m "still older than 3"
git commit -q --allow-empty -m "fix(docs): correct the date"
expect "3.0.1"

echo "next-version ok"
