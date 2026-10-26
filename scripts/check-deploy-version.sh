#!/usr/bin/env bash
# Portal stamp and version tag: MAJOR.MINOR.PATCH. Not YEAR-MONTH_rN.
set -euo pipefail

version="${1:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "version is not MAJOR.MINOR.PATCH" >&2
  exit 1
fi
