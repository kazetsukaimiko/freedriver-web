#!/usr/bin/env bash
# Install the pinned git-cliff binary into .tools/bin. Not committed.
set -euo pipefail

VERSION="2.14.2"
SHA512="26d1f7c8ea2400f2ccb0b2e7f321635f600b583bf4cd1f7afaa5e8c24068ad1088a2504e0a4d9f4333d4bb20a80329327d9462634343ee3af91e1a7fbdc6f18a"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/.tools/bin"
NAME="git-cliff-${VERSION}-x86_64-unknown-linux-gnu.tar.gz"

if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "git-cliff install supports x86_64 only" >&2
  exit 1
fi
if [[ -x "${DEST}/git-cliff" ]] && "${DEST}/git-cliff" --version | grep -q "${VERSION}"; then
  exit 0
fi

mkdir -p "$DEST"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
url="https://github.com/orhun/git-cliff/releases/download/v${VERSION}/${NAME}"
curl -fsSL -o "${tmp}/${NAME}" "$url"
actual="$(sha512sum "${tmp}/${NAME}" | awk '{print $1}')"
if [[ "$actual" != "$SHA512" ]]; then
  echo "git-cliff checksum mismatch" >&2
  exit 1
fi
tar -xzf "${tmp}/${NAME}" -C "$tmp"
install -m 755 "${tmp}/git-cliff-${VERSION}/git-cliff" "${DEST}/git-cliff"
