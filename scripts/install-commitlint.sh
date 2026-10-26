#!/usr/bin/env bash
# Install pinned commitlint into .tools/commitlint. Not committed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/.tools/commitlint"
if [[ -x "${DEST}/node_modules/.bin/commitlint" ]]; then
  exit 0
fi
mkdir -p "$DEST"
npm install --prefix "$DEST" --no-fund --no-audit --loglevel=error \
  @commitlint/cli@19.8.1 \
  @commitlint/config-conventional@19.8.1
