#!/usr/bin/env bash
# Repo-file checks for the per-service Mongo cutover (#505).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${ROOT}/scripts/provision-mongo-users.sh" --check
