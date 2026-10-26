#!/usr/bin/env bash
# Write the plain-text changelog the image packages. The file is not committed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TZ="${TZ:-UTC}"
repo="${CHANGELOG_REPO:-$ROOT}"
out="${CHANGELOG_OUT:-${ROOT}/java/dashboard/src/main/resources/changelog/CHANGELOG.md}"

if [[ ! -x "${ROOT}/.tools/bin/git-cliff" ]]; then
  "${ROOT}/scripts/install-git-cliff.sh"
fi
mkdir -p "$(dirname "$out")"
"${ROOT}/.tools/bin/git-cliff" --config "${ROOT}/cliff.toml" -r "$repo" -o "$out"
python3 - "$out" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.write_text(path.read_text().strip() + "\n")
PY
