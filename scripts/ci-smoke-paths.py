#!/usr/bin/env python3
"""Print `true` when a pull request should run the compose smoke job."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


PATTERNS = (
    "docker-compose",
    "Caddyfile",
    "keycloak/",
    "Dockerfile",
    "application",
    "auth-policy.properties",
    "scripts/ci-smoke",
    "ci/",
    ".github/workflows/ci.yml",
)


def names_from_event(event: dict) -> list[str]:
    names: list[str] = []
    pr = event.get("pull_request") or {}
    files = pr.get("files") or []
    for item in files:
        name = item.get("filename") or item.get("name")
        if name:
            names.append(name)
    for commit in event.get("commits") or []:
        names.extend(commit.get("added") or [])
        names.extend(commit.get("modified") or [])
        names.extend(commit.get("removed") or [])
    return names


def names_from_git() -> list[str]:
    base = os.environ.get("SMOKE_BASE_SHA") or os.environ.get("GITHUB_BASE_REF")
    if not base:
        return []
    if "/" not in base and not _is_sha(base):
        base = f"origin/{base}"
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return []
    return [line for line in result.stdout.splitlines() if line]


def _is_sha(value: str) -> bool:
    return len(value) >= 7 and all(ch in "0123456789abcdef" for ch in value.lower())


def matches(path: str) -> bool:
    name = Path(path).name
    if name.startswith("application") and name.endswith(".properties"):
        return True
    if "Dockerfile" in name:
        return True
    return any(token in path for token in PATTERNS)


def self_test() -> None:
    hits = (
        "docker-compose.yml",
        "docker-compose.ci.yml",
        "Caddyfile",
        "Caddyfile.ci",
        "keycloak/Dockerfile",
        "sms/src/main/docker/Dockerfile.compose",
        "sms/src/main/resources/application.properties",
        "java/dashboard/src/main/resources/application.properties",
        "java/dashboard/src/main/resources/auth-policy.properties",
        "scripts/ci-smoke.sh",
        "ci/twilio-verify-stub/stub.py",
        ".github/workflows/ci.yml",
    )
    misses = ("README.md", "docs/appliances.md", "site/index.html")
    for path in hits:
        if not matches(path):
            raise SystemExit(f"expected smoke path {path}")
    for path in misses:
        if matches(path):
            raise SystemExit(f"unexpected smoke path {path}")


def main() -> int:
    if "--self-test" in sys.argv:
        self_test()
        print("ci-smoke-paths self-test ok")
        return 0
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    ref = os.environ.get("GITHUB_REF", "")
    if event_name == "push" and ref == "refs/heads/main":
        print("true")
        return 0
    names: list[str] = []
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    if event_path and Path(event_path).is_file():
        event = json.loads(Path(event_path).read_text(encoding="utf-8"))
        names.extend(names_from_event(event))
        if not os.environ.get("SMOKE_BASE_SHA"):
            sha = ((event.get("pull_request") or {}).get("base") or {}).get("sha")
            if sha:
                os.environ["SMOKE_BASE_SHA"] = sha
    names.extend(names_from_git())
    unique = sorted(set(names))
    hit = [path for path in unique if matches(path)]
    print("true" if hit else "false")
    if hit:
        print("smoke paths:", *hit[:40], file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
