#!/usr/bin/env python3
"""Print `true` when a pull request should start throwaway Keycloak provision tests."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


PATTERNS = (
    "scripts/provision-keycloak",
    "scripts/test-sms-otp-scaffold.sh",
    "scripts/test-invite-service-account.sh",
    "scripts/test-autonomy-token-exchange.sh",
    "scripts/ci-keycloak-test-paths.py",
    "keycloak/",
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
    return any(token in path for token in PATTERNS)


def self_test() -> None:
    hits = (
        "scripts/provision-keycloak-freedriver.sh",
        "scripts/provision-keycloak-sms-otp.sh",
        "scripts/test-sms-otp-scaffold.sh",
        "scripts/test-invite-service-account.sh",
        "scripts/test-autonomy-token-exchange.sh",
        "scripts/ci-keycloak-test-paths.py",
        "keycloak/Dockerfile",
        "keycloak/sms-otp-spi/pom.xml",
    )
    misses = (
        "docker-compose.yml",
        "Caddyfile",
        "README.md",
        ".github/workflows/ci.yml",
        "java/dashboard/src/main/resources/application.properties",
    )
    for path in hits:
        if not matches(path):
            raise SystemExit(f"expected keycloak-test path {path}")
    for path in misses:
        if matches(path):
            raise SystemExit(f"unexpected keycloak-test path {path}")


def main() -> int:
    if "--self-test" in sys.argv:
        self_test()
        print("ci-keycloak-test-paths self-test ok")
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
        print("keycloak-test paths:", *hit[:40], file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
