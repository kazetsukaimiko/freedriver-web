#!/usr/bin/env python3
"""Report Vale's writing-policy warnings on what a pull request adds (freedriver-web #561).

  vale_changed_lines.py files --base <sha> --head <sha>
      Lints the Markdown files and .env.example comments the pull request changes and prints a
      GitHub warning annotation for each Vale alert on a line the pull request added. Text the
      pull request leaves as it was produces no annotation.
  vale_changed_lines.py body <file>
      Lints the pull request description saved in <file> and writes its warnings, quoting each
      line, to the job summary ($GITHUB_STEP_SUMMARY, or stdout).
  vale_changed_lines.py --self-test

Warnings only: every mode exits 0, and a failure to run Vale becomes one warning.
"""
from __future__ import annotations

import fnmatch
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

CONFIG = ".vale.ini"
LINTED = ("*.md", "*.env.example")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def vale_binary() -> str:
    return os.environ.get("VALE", "vale")


def linted(path: str) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in LINTED)


def added_lines(diff: str) -> dict[str, set[int]]:
    """Path -> line numbers (in the new file) that the unified=0 diff adds."""
    out: dict[str, set[int]] = {}
    path = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            path = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
            continue
        m = HUNK.match(line)
        if m and path is not None:
            start, count = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
            out.setdefault(path, set()).update(range(start, start + count))
    return {p: lines for p, lines in out.items() if lines}


def comments_only(text: str) -> str:
    """A .env.example with every line that is not a comment blanked, line numbers kept."""
    return "\n".join(line if line.lstrip().startswith("#") else "" for line in text.splitlines()) + "\n"


def run_vale(path: Path) -> list[dict]:
    """Vale's alerts for one file, as JSON objects with Line, Message, Match, Check."""
    out = subprocess.run([vale_binary(), "--config", CONFIG, "--output", "JSON", "--no-exit", str(path)],
                         capture_output=True, text=True)
    if not out.stdout.strip():
        if out.returncode:
            raise RuntimeError(out.stderr.strip() or f"vale exited {out.returncode}")
        return []
    data = json.loads(out.stdout)
    return [alert for alerts in data.values() for alert in alerts]


def annotation(path: str, alert: dict) -> str:
    message = str(alert.get("Message", "")).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return f"::warning file={path},line={alert['Line']},title=Writing policy::{message}"


def file_annotations(path: str, alerts: list[dict], added: set[int]) -> list[str]:
    return [annotation(path, a) for a in alerts if a.get("Line") in added]


def lint_file(path: str, text: str) -> list[dict]:
    if path.endswith(".env.example"):
        text = comments_only(text)
    with tempfile.TemporaryDirectory() as d:
        target = Path(d) / "lint.md"
        target.write_text(text)
        return run_vale(target)


def files_mode(base: str, head: str) -> list[str]:
    diff = subprocess.run(["git", "diff", "--unified=0", "--no-color", f"{base}...{head}"],
                          capture_output=True, text=True, check=True).stdout
    lines = []
    for path, added in sorted(added_lines(diff).items()):
        if not linted(path) or not Path(path).is_file():
            continue
        lines += file_annotations(path, lint_file(path, Path(path).read_text()), added)
    return lines


def body_summary(text: str, alerts: list[dict]) -> str:
    rows = text.splitlines()
    if not alerts:
        return "### Writing policy: pull request description\n\nVale has no warning on the description.\n"
    out = ["### Writing policy: pull request description", ""]
    for a in sorted(alerts, key=lambda a: a.get("Line", 0)):
        n = a.get("Line", 0)
        quoted = rows[n - 1].strip() if 0 < n <= len(rows) else ""
        out.append(f"- Line {n}: {a.get('Message', '')}")
        out.append(f"  > {quoted}")
    return "\n".join(out) + "\n"


def body_mode(path: str) -> str:
    text = Path(path).read_text()
    alerts = lint_file("description.md", text)
    for a in alerts:
        print(f"::warning title=Writing policy (description)::line {a.get('Line')}: {a.get('Message', '')}")
    return body_summary(text, alerts)


def summary_out(text: str) -> None:
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a") as f:
            f.write(text)
    else:
        sys.stdout.write(text)


def self_test() -> int:
    diff = (
        "diff --git a/docs/a.md b/docs/a.md\n--- a/docs/a.md\n+++ b/docs/a.md\n"
        "@@ -3,0 +4,2 @@\n+one\n+two\n@@ -10 +12 @@\n-old\n+new\n"
        "diff --git a/gone.md b/gone.md\n--- a/gone.md\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n"
        "diff --git a/x.java b/x.java\n--- a/x.java\n+++ b/x.java\n@@ -1,0 +1 @@\n+y\n"
        "diff --git a/b.md b/b.md\n--- a/b.md\n+++ b/b.md\n@@ -5,2 +4,0 @@\n-a\n-b\n"
    )
    added = added_lines(diff)
    assert added == {"docs/a.md": {4, 5, 12}, "x.java": {1}}, added
    assert linted("docs/a.md") and linted("README.md") and linted(".env.example") and linted("sms/.env.example")
    assert linted("secrets/sms-otp.env.example") and linted("secrets/keycloak-freedriver-invite.env.example")
    assert not linted("x.java") and not linted("docs/diagram.svg")

    alerts = [{"Line": 4, "Message": "Writing policy: ('does not')", "Match": "does not"},
              {"Line": 7, "Message": "on an unchanged line", "Match": "is not"}]
    got = file_annotations("docs/a.md", alerts, added["docs/a.md"])
    assert got == ["::warning file=docs/a.md,line=4,title=Writing policy::Writing policy: ('does not')"], got
    assert annotation("f.md", {"Line": 1, "Message": "100% sure\nnext"}).endswith("::100%25 sure%0Anext")

    env = "# The key does not leave the host\nKEY=does not matter\n  # indented note\n"
    assert comments_only(env) == "# The key does not leave the host\n\n  # indented note\n"

    body = "First line.\nThe job does not fail.\n"
    summary = body_summary(body, [{"Line": 2, "Message": "Writing policy: ('does not')"}])
    assert "- Line 2: Writing policy: ('does not')" in summary and "  > The job does not fail." in summary, summary
    assert "no warning" in body_summary(body, [])
    print("vale_changed_lines self-test: ok")
    return 0


def main(argv: list[str]) -> int:
    if argv[:1] == ["--self-test"]:
        return self_test()
    try:
        if argv[:1] == ["files"] and len(argv) == 5 and argv[1] == "--base" and argv[3] == "--head":
            for line in files_mode(argv[2], argv[4]):
                print(line)
        elif argv[:1] == ["body"] and len(argv) == 2:
            summary_out(body_mode(argv[1]))
        else:
            print(__doc__, file=sys.stderr)
    except Exception as error:  # warnings only: report, never fail the job
        print(f"::warning title=Writing policy::Vale did not run: {error}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
