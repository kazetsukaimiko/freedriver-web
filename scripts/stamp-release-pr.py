#!/usr/bin/env python3
"""Rewrite a release-please changelog heading and version.txt to YEAR-MONTH_rN."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

STAMP_RE = re.compile(r"^(20[0-9]{2}-[0-9]{2})_r([0-9]+)$")
HEADING_STAMP_RE = re.compile(
    r"^## (20[0-9]{2}-[0-9]{2}_r[0-9]+)(?: \((\d{4}-\d{2}-\d{2})\))?\s*$"
)
HEADING_ANY_RE = re.compile(r"^## .+")
DATE_IN_HEADING_RE = re.compile(r"\((\d{4}-\d{2}-\d{2})\)\s*$")


def parse_stamp(value: str) -> tuple[str, int] | None:
    text = value.strip()
    match = STAMP_RE.fullmatch(text)
    if not match:
        return None
    return match.group(1), int(match.group(2))


def next_stamp(today_ym: str, known: list[str]) -> str:
    highest = 0
    for item in known:
        parsed = parse_stamp(item)
        if parsed and parsed[0] == today_ym:
            highest = max(highest, parsed[1])
    return f"{today_ym}_r{highest + 1}"


def heading_stamp(changelog: str) -> str | None:
    for line in changelog.splitlines():
        match = HEADING_STAMP_RE.fullmatch(line)
        if match:
            return match.group(1)
        if HEADING_ANY_RE.match(line) and not line.startswith("# Changelog"):
            return None
    return None


def rewrite_changelog(changelog: str, stamp: str) -> str:
    lines = changelog.splitlines(keepends=True)
    if not lines:
        return f"# Changelog\n\n## {stamp}\n"
    replaced = False
    out: list[str] = []
    for line in lines:
        raw = line.splitlines()[0] if line.endswith("\n") else line
        newline = "\n" if line.endswith("\n") else ""
        if not replaced and HEADING_ANY_RE.match(raw) and not raw.startswith("# Changelog"):
            date_match = DATE_IN_HEADING_RE.search(raw)
            suffix = f" ({date_match.group(1)})" if date_match else ""
            out.append(f"## {stamp}{suffix}{newline}")
            replaced = True
        else:
            out.append(line)
    if not replaced:
        body = "".join(out)
        if body and not body.endswith("\n"):
            body += "\n"
        return body + f"\n## {stamp}\n"
    return "".join(out)


def load_known(args: argparse.Namespace) -> list[str]:
    known = list(args.known_stamp)
    if args.known_stamps_file:
        text = Path(args.known_stamps_file).read_text(encoding="utf-8")
        known.extend(line.strip() for line in text.splitlines() if line.strip())
    return known


def git(*git_args: str, cwd: Path) -> None:
    subprocess.run(["git", *git_args], cwd=cwd, check=True)


def maybe_commit_and_title(root: Path, stamp: str, pr_number: str | None) -> None:
    git("add", "--", "version.txt", "java/dashboard/src/main/resources/changelog/CHANGELOG.md", cwd=root)
    status = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=root,
        check=False,
    )
    if status.returncode != 0:
        git("config", "user.name", "github-actions[bot]", cwd=root)
        git(
            "config",
            "user.email",
            "41898282+github-actions[bot]@users.noreply.github.com",
            cwd=root,
        )
        git("commit", "-m", f"chore: stamp release {stamp}", cwd=root)
        git("push", cwd=root)
    if pr_number:
        subprocess.run(
            ["gh", "pr", "edit", pr_number, "--title", f"chore(main): release {stamp}"],
            check=True,
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--changelog", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--today", default="")
    parser.add_argument("--known-stamp", action="append", default=[])
    parser.add_argument("--known-stamps-file", type=Path)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--commit-push", action="store_true")
    parser.add_argument("--pr-json", default="")
    args = parser.parse_args(argv)

    today = args.today or datetime.now(timezone.utc).strftime("%Y-%m")
    changelog = args.changelog.read_text(encoding="utf-8") if args.changelog.exists() else ""
    existing = heading_stamp(changelog)
    stamp = existing or next_stamp(today, load_known(args))
    new_changelog = rewrite_changelog(changelog, stamp)
    new_version = f"{stamp}\n"

    if args.write:
        args.changelog.parent.mkdir(parents=True, exist_ok=True)
        args.changelog.write_text(new_changelog, encoding="utf-8")
        args.version_file.write_text(new_version, encoding="utf-8")
        if args.commit_push:
            pr_number = None
            raw = args.pr_json or os.environ.get("PR_JSON", "")
            if raw.strip():
                payload = json.loads(raw)
                pr_number = str(payload["number"])
            maybe_commit_and_title(args.version_file.parent.resolve(), stamp, pr_number)

    sys.stdout.write(stamp + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
