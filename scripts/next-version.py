#!/usr/bin/env python3
"""Print the next MAJOR.MINOR.PATCH.

fix and every other commit since the newest semver tag bump the patch.
feat bumps the minor. A "!" or a BREAKING CHANGE footer bumps the major.
The first MAJOR.MINOR.PATCH tag, when none exist yet, is 1.0.0.
"""

import re
import subprocess
import sys

SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
BANG = re.compile(r"^[a-z]+(\([^)]+\))?!:")
FEAT = re.compile(r"^feat(\([^)]+\))?!?:")
BREAKING = re.compile(r"(?m)^BREAKING CHANGE: \S")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True)


def main() -> None:
    tags = [line for line in git("tag", "--list").splitlines() if SEMVER.fullmatch(line)]
    if not tags:
        print("1.0.0")
        return
    latest = max(tags, key=lambda tag: tuple(int(part) for part in tag.split(".")))
    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", latest, "HEAD"],
        check=False,
    )
    if ancestor.returncode != 0:
        print(f"{latest} is not an ancestor of HEAD", file=sys.stderr)
        sys.exit(1)
    head = git("rev-parse", "HEAD^{commit}").strip()
    tagged = git("rev-parse", f"{latest}^{{commit}}").strip()
    if head == tagged:
        print(f"HEAD is already {latest}", file=sys.stderr)
        sys.exit(1)
    log = git("log", f"{latest}..HEAD", "--format=%s%x00%b%x1e")
    breaking = False
    feat = False
    for record in log.split("\x1e"):
        record = record.strip("\n")
        if not record.strip():
            continue
        subject, _, body = record.partition("\x00")
        subject = subject.strip()
        if BANG.match(subject) or BREAKING.search(body):
            breaking = True
        if FEAT.match(subject) and not BANG.match(subject):
            feat = True
    major, minor, patch = (int(part) for part in latest.split("."))
    if breaking:
        print(f"{major + 1}.0.0")
    elif feat:
        print(f"{major}.{minor + 1}.0")
    else:
        print(f"{major}.{minor}.{patch + 1}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        print(exc, file=sys.stderr)
        sys.exit(exc.returncode or 1)
