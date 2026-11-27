#!/usr/bin/env python3
"""Print `true` when a pull request should run a named CI path-gated job."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import NamedTuple


class PathSet(NamedTuple):
    label: str
    patterns: tuple[str, ...]
    hits: tuple[str, ...]
    misses: tuple[str, ...]
    dockerfile_in_name: bool
    application_properties: bool


SETS: dict[str, PathSet] = {
    "smoke": PathSet(
        label="smoke",
        patterns=(
            "docker-compose",
            "Caddyfile",
            "keycloak/",
            "Dockerfile",
            "application",
            "auth-policy.properties",
            "scripts/ci-smoke",
            "scripts/provision-keycloak",
            "ci/",
            ".github/workflows/ci.yml",
        ),
        hits=(
            "docker-compose.yml",
            "docker-compose.ci.yml",
            "Caddyfile",
            "keycloak/Dockerfile",
            "scripts/provision-keycloak-freedriver.sh",
            "scripts/provision-keycloak-sms-otp.sh",
            "sms/src/main/docker/Dockerfile.compose",
            "sms/src/main/resources/application.properties",
            "java/dashboard/src/main/resources/application.properties",
            "java/dashboard/src/main/resources/auth-policy.properties",
            "scripts/ci-smoke.sh",
            "ci/twilio-verify-stub/stub.py",
            ".github/workflows/ci.yml",
        ),
        misses=("README.md", "docs/appliances.md", "site/index.html"),
        dockerfile_in_name=True,
        application_properties=True,
    ),
    "keycloak": PathSet(
        label="keycloak-test",
        patterns=(
            "scripts/provision-keycloak",
            "scripts/test-sms-otp-scaffold.sh",
            "scripts/test-invite-service-account.sh",
            "scripts/test-autonomy-token-exchange.sh",
            "scripts/check-app-audience.py",
            "scripts/fixtures/app-audience-ok.json",
            "scripts/ci-smoke-paths.py",
            "keycloak/",
        ),
        hits=(
            "scripts/provision-keycloak-freedriver.sh",
            "scripts/provision-keycloak-sms-otp.sh",
            "scripts/test-sms-otp-scaffold.sh",
            "scripts/test-invite-service-account.sh",
            "scripts/test-autonomy-token-exchange.sh",
            "scripts/check-app-audience.py",
            "scripts/fixtures/app-audience-ok.json",
            "scripts/ci-smoke-paths.py",
            "keycloak/Dockerfile",
            "keycloak/sms-otp-spi/pom.xml",
        ),
        misses=(
            "docker-compose.yml",
            "Caddyfile",
            "README.md",
            ".github/workflows/ci.yml",
            "java/dashboard/src/main/resources/application.properties",
        ),
        dockerfile_in_name=False,
        application_properties=False,
    ),
}


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


def names_from_git() -> tuple[list[str], bool]:
    """Return (names, unknown). unknown is True when git cannot tell which files changed."""
    base = os.environ.get("SMOKE_BASE_SHA") or os.environ.get("GITHUB_BASE_REF")
    if not base:
        return [], True
    if "/" not in base and not _is_sha(base):
        base = f"origin/{base}"
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return [], True
    return [line for line in result.stdout.splitlines() if line], False


def _is_sha(value: str) -> bool:
    return len(value) >= 7 and all(ch in "0123456789abcdef" for ch in value.lower())


def matches(path: str, path_set: PathSet) -> bool:
    name = Path(path).name
    if path_set.application_properties and name.startswith("application") and name.endswith(
        ".properties"
    ):
        return True
    if path_set.dockerfile_in_name and "Dockerfile" in name:
        return True
    return any(token in path for token in path_set.patterns)


def should_run(names: list[str], unknown: bool, path_set: PathSet) -> bool:
    if any(matches(path, path_set) for path in names):
        return True
    return unknown


def self_test(set_name: str | None) -> None:
    names = [set_name] if set_name else list(SETS)
    for name in names:
        path_set = SETS.get(name)
        if path_set is None:
            raise SystemExit(f"unknown path set {name}")
        for path in path_set.hits:
            if not matches(path, path_set):
                raise SystemExit(f"expected {path_set.label} path {path}")
        for path in path_set.misses:
            if matches(path, path_set):
                raise SystemExit(f"unexpected {path_set.label} path {path}")
        if not should_run([], True, path_set):
            raise SystemExit(f"{path_set.label} cannot-tell must print true")
        if should_run([], False, path_set):
            raise SystemExit(f"{path_set.label} empty known diff must print false")
        if not should_run([path_set.hits[0]], True, path_set):
            raise SystemExit(f"{path_set.label} hit plus cannot-tell must print true")


def parse_args(argv: list[str]) -> tuple[bool, str | None]:
    self_test_mode = "--self-test" in argv
    names = [a for a in argv[1:] if a != "--self-test"]
    if len(names) > 1:
        raise SystemExit("usage: ci-smoke-paths.py [--self-test] [smoke|keycloak]")
    return self_test_mode, names[0] if names else None


def main() -> int:
    self_test_mode, set_name = parse_args(sys.argv)
    if self_test_mode:
        self_test(set_name)
        which = set_name or "all"
        print(f"ci-smoke-paths {which} self-test ok")
        return 0
    if set_name is None:
        set_name = "smoke"
    path_set = SETS.get(set_name)
    if path_set is None:
        raise SystemExit(f"unknown path set {set_name}")
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
    git_names, unknown = names_from_git()
    names.extend(git_names)
    unique = sorted(set(names))
    hit = [path for path in unique if matches(path, path_set)]
    run = should_run(unique, unknown, path_set)
    print("true" if run else "false")
    if hit:
        print(f"{path_set.label} paths:", *hit[:40], file=sys.stderr)
    elif unknown:
        print(f"{path_set.label} cannot tell which files changed", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
