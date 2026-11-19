#!/usr/bin/env python3
"""A pull request cannot freeze a new layering violation unnoticed (#585).

The layering tests (#560) pass every violation recorded under a module's
`src/test/resources/archunit-store`. Their failure message gives the command that re-records a
store, and run on new code that command freezes the new violation. This check compares the
stores of the pull request with those of its base, so the stores can only shrink:

- Violation lines are counted per module and rule (the key in `stored.rules`), without their
  source line number: `in (File.java:84)` is compared as `in (File.java)`, because a re-record
  writes today's line numbers.
- A rule whose key is on the base is compared with that rule's base lines.
- A rule whose key is new (its text changed, or it is a new rule) is compared with every
  violation line of that module on the base. A re-record keeps the old key, so a reworded rule
  whose violations are unchanged finds them there and passes; a new violation does not.
- Removing lines (a fixed violation) passes.

An intended new frozen violation passes only with the label `freeze-new-violation`, and only
when kazetsukaimiko added it: the check reads who added it from the pull request's issue events,
because every agent account can add a label. The check says when it passes on the label. After
adding the label, re-run the failed `test` job. The check does not run ArchUnit.

Usage:
  python3 .github/scripts/archunit_store_check.py --self-test
  python3 .github/scripts/archunit_store_check.py <base-sha>   compare HEAD with <base-sha>
      in a pull request also set GITHUB_REPOSITORY, PR_NUMBER and GH_TOKEN for the label
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from collections import Counter
from pathlib import Path

STORE = "src/test/resources/archunit-store"
RULES = "stored.rules"
LABEL = "freeze-new-violation"
LABEL_OWNER = "kazetsukaimiko"
LINE_NUMBER = re.compile(r"\(([^()\s:]+\.java):\d+\)")


def normalized(line: str) -> str:
    return LINE_NUMBER.sub(r"(\1)", line)


def parse_properties(text: str) -> dict[str, str]:
    """The key=value pairs of a java.util.Properties file, as ArchUnit writes stored.rules."""
    out: dict[str, str] = {}
    logical, pending = [], ""
    for raw in text.splitlines():
        line = pending + raw.lstrip() if pending else raw
        if _continues(line):
            pending = line[:-1]
            continue
        pending = ""
        logical.append(line)
    for line in logical:
        stripped = line.lstrip()
        if not stripped or stripped[0] in "#!":
            continue
        key, value = _split(stripped)
        out[_unescape(key)] = _unescape(value)
    return out


def _continues(line: str) -> bool:
    backslashes = len(line) - len(line.rstrip("\\"))
    return backslashes % 2 == 1


def _split(line: str) -> tuple[str, str]:
    i = 0
    while i < len(line):
        c = line[i]
        if c == "\\":
            i += 2
            continue
        if c in "=: \t\f":
            key, rest = line[:i], line[i:].lstrip(" \t\f")
            if rest[:1] in ("=", ":") and c in " \t\f":
                rest = rest[1:].lstrip(" \t\f")
            elif c in "=:":
                rest = rest[1:].lstrip(" \t\f")
            return key, rest
        i += 1
    return line, ""


def _unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and i + 6 <= len(s):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


class Tree:
    """Reads files from the working tree (rev None) or from a commit."""

    def __init__(self, repo: Path, rev: str | None):
        self.repo, self.rev = repo, rev

    def stores(self) -> list[str]:
        """Module-relative store directories, as repository paths."""
        if self.rev is None:
            found = [p.relative_to(self.repo).as_posix() for p in self.repo.rglob(f"{STORE}/{RULES}")]
        else:
            names = subprocess.run(["git", "-C", str(self.repo), "ls-tree", "-r", "--name-only", self.rev],
                                   check=True, capture_output=True, text=True).stdout.splitlines()
            found = [n for n in names if n.endswith(f"/{STORE}/{RULES}") or n == f"{STORE}/{RULES}"]
        return sorted(f[: -len(RULES) - 1] for f in found)

    def read(self, path: str) -> str | None:
        if self.rev is None:
            p = self.repo / path
            return p.read_text() if p.is_file() else None
        out = subprocess.run(["git", "-C", str(self.repo), "show", f"{self.rev}:{path}"],
                             capture_output=True, text=True)
        return out.stdout if out.returncode == 0 else None


def store_lines(tree: Tree, store: str) -> dict[str, tuple[str, list[str]]]:
    """rule -> (store file path, violation lines) for one store directory."""
    text = tree.read(f"{store}/{RULES}")
    if text is None:
        return {}
    out = {}
    for rule, name in parse_properties(text).items():
        path = f"{store}/{name}"
        content = tree.read(path) or ""
        out[rule] = (path, [l for l in content.splitlines() if l.strip()])
    return out


def added_lines(repo: Path, base: str, head: str | None = None) -> list[str]:
    """One line per violation line the head adds to a store, naming the file and the line."""
    base_tree, head_tree = Tree(repo, base), Tree(repo, head)
    found = []
    for store in sorted(set(head_tree.stores())):
        module = store[: -len(STORE)].rstrip("/") or "."
        before = store_lines(base_tree, store)
        after = store_lines(head_tree, store)
        everything_before = Counter(normalized(l) for _, lines in before.values() for l in lines)
        for rule, (path, lines) in sorted(after.items()):
            allowed = Counter(normalized(l) for l in before[rule][1]) if rule in before else Counter(everything_before)
            for line in lines:
                key = normalized(line)
                if allowed[key] > 0:
                    allowed[key] -= 1
                else:
                    found.append(f"{module}: {path} adds a frozen violation: {line}")
    return found


def label_added_by_owner(events: list[dict]) -> tuple[bool, str]:
    """Whether the label is on the pull request now and its latest adding was by LABEL_OWNER."""
    on, by = False, ""
    for e in events:
        if (e.get("label") or {}).get("name") != LABEL:
            continue
        if e.get("event") == "labeled":
            on, by = True, (e.get("actor") or {}).get("login", "")
        elif e.get("event") == "unlabeled":
            on, by = False, ""
    return on and by == LABEL_OWNER, by


def issue_events(repo: str, number: str, token: str) -> list[dict]:
    events, page = [], 1
    while True:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/issues/{number}/events?per_page=100&page={page}",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            batch = json.load(resp)
        events += batch
        if len(batch) < 100:
            return events
        page += 1


def check(repo: Path, base: str, events_of=None, say=print) -> int:
    lines = added_lines(repo, base)
    if not lines:
        say("no store under archunit-store gains a frozen violation")
        return 0
    events = events_of() if events_of else []
    accepted, by = label_added_by_owner(events)
    if accepted:
        say(f"{len(lines)} new frozen violation(s), accepted: {LABEL_OWNER} added the {LABEL} label")
        for line in lines:
            say(f"  {line}")
        return 0
    for line in lines:
        say(f"::error::{line}")
    note = f" (the label was added by {by}, not {LABEL_OWNER})" if by else ""
    say(f"{len(lines)} new frozen violation(s). Fix the code so the layering test passes without them, "
          f"or ask {LABEL_OWNER} to add the {LABEL} label and re-run this job{note}.")
    return 1


def self_test() -> int:
    checks = []
    with tempfile.TemporaryDirectory() as d:
        repo = Path(d)

        def git(*args):
            subprocess.run(["git", "-C", d, *args], check=True, capture_output=True,
                           env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                                "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"})

        def write(stores: dict[str, dict[str, list[str]]]):
            for store in repo.rglob("archunit-store"):
                for f in store.iterdir():
                    f.unlink()
            for module, rules in stores.items():
                store = repo / module / STORE
                store.mkdir(parents=True, exist_ok=True)
                props = ["#", "#Tue Nov 17 21:14:54 UTC 2026"]
                for i, (rule, lines) in enumerate(rules.items()):
                    props.append(rule.replace(" ", "\\ ").replace(":", "\\:") + f"=id-{abs(hash(rule))}")
                    (store / f"id-{abs(hash(rule))}").write_text("".join(l + "\n" for l in lines))
                (store / RULES).write_text("\n".join(props) + "\n")

        def commit():
            git("add", "-A")
            git("commit", "-q", "--allow-empty", "-m", "x")
            return subprocess.run(["git", "-C", d, "rev-parse", "HEAD"], check=True,
                                  capture_output=True, text=True).stdout.strip()

        git("init", "-q")
        rule = "no classes should depend on 'jakarta.ws.rs..', because: edge"
        a = "Method <a.B.c()> calls method <jakarta.ws.rs.X.y()> in (B.java:84)"
        b = "Method <a.B.d()> calls method <jakarta.ws.rs.X.z()> in (B.java:90)"
        write({"java/svc": {rule: [a, b]}, "sms": {}})
        base = commit()

        checks.append(("an unchanged store passes", added_lines(repo, base) == []))

        write({"java/svc": {rule: [a, b, "Method <a.N.e()> calls method <jakarta.ws.rs.X.y()> in (N.java:3)"]}, "sms": {}})
        lines = added_lines(repo, base)
        checks.append(("a new violation fails with the file and the line", len(lines) == 1
                       and lines[0].startswith("java/svc: java/svc/src/test/resources/archunit-store/id-")
                       and lines[0].endswith("adds a frozen violation: Method <a.N.e()> calls method "
                                             "<jakarta.ws.rs.X.y()> in (N.java:3)")))

        write({"java/svc": {rule: [a]}, "sms": {}})
        checks.append(("a fixed violation passes", added_lines(repo, base) == []))

        write({"java/svc": {rule: [a.replace(":84", ":85"), b.replace(":90", ":12")]}, "sms": {}})
        checks.append(("line numbers are not compared", added_lines(repo, base) == []))

        write({"java/svc": {rule: [a, a]}, "sms": {}})
        checks.append(("a second copy of a frozen line is a new violation", len(added_lines(repo, base)) == 1))

        reworded = rule.replace("edge", "edge; move it")
        write({"java/svc": {reworded: [a.replace(":84", ":86"), b]}, "sms": {}})
        checks.append(("a reworded rule with the same violations passes", added_lines(repo, base) == []))
        write({"java/svc": {rule: [a, b], reworded: [a.replace(":84", ":86"), b]}, "sms": {}})
        checks.append(("a reworded rule next to its kept old key passes", added_lines(repo, base) == []))
        write({"java/svc": {reworded: [a, b, "Method <a.N.e()> calls x in (N.java:1)"]}, "sms": {}})
        checks.append(("a reworded rule with a new violation fails", len(added_lines(repo, base)) == 1))

        write({"java/svc": {rule: [a, b]}, "sms": {"no classes should x": ["Method <s.T.u()> calls x in (T.java:5)"]}})
        lines = added_lines(repo, base)
        checks.append(("a module outside java/ is checked", len(lines) == 1 and lines[0].startswith("sms: ")))

        write({"java/svc": {rule: [a, b]}, "sms": {}, "mqtt": {"no classes should x": ["Method calls x in (M.java:2)"]}})
        lines = added_lines(repo, base)
        checks.append(("a new store counts every line", len(lines) == 1 and lines[0].startswith("mqtt: ")))

        checks.append(("a rule key is read with properties escapes", parse_properties(
            "#c\na\\ b\\:\\ c\\=d=id-1\n") == {"a b: c=d": "id-1"}))

        write({"java/svc": {rule: [a, b, "Method <a.N.e()> calls x in (N.java:3)"]}, "sms": {}})
        commit()
        quiet = lambda *a: None
        lab = lambda event, who: {"event": event, "label": {"name": LABEL}, "actor": {"login": who}}
        other = {"event": "labeled", "label": {"name": "manual-review"}, "actor": {"login": LABEL_OWNER}}
        checks.append(("the check fails without the label", check(repo, base, lambda: [other], say=quiet) == 1))
        checks.append(("the label from kazetsukaimiko passes",
                       check(repo, base, lambda: [lab("labeled", LABEL_OWNER)], say=quiet) == 0))
        checks.append(("the label from an agent account fails",
                       check(repo, base, lambda: [lab("labeled", "lonewatt-architect")], say=quiet) == 1))
        checks.append(("a label removed after kazetsukaimiko added it fails",
                       check(repo, base, lambda: [lab("labeled", LABEL_OWNER),
                                                  lab("unlabeled", LABEL_OWNER)], say=quiet) == 1))
        checks.append(("the latest adding counts", check(repo, base, lambda: [
            lab("labeled", LABEL_OWNER), lab("unlabeled", "kazetsukaiyuni"),
            lab("labeled", "kazetsukaiyuni")], say=quiet) == 1))
    failed = [name for name, ok in checks if not ok]
    for name in failed:
        print(f"FAIL: {name}")
    print(f"archunit_store_check self-test: {len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    if len(sys.argv) != 2 or sys.argv[1].startswith("-"):
        print(__doc__.split("Usage:")[1], file=sys.stderr)
        return 2
    repo = Path(__file__).resolve().parents[2]
    gh_repo, number, token = (os.environ.get(k, "") for k in ("GITHUB_REPOSITORY", "PR_NUMBER", "GH_TOKEN"))
    events_of = (lambda: issue_events(gh_repo, number, token)) if gh_repo and number and token else None
    return check(repo, sys.argv[1], events_of)


if __name__ == "__main__":
    sys.exit(main())
