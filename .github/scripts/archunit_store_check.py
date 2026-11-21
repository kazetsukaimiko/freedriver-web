#!/usr/bin/env python3
"""A pull request cannot freeze a new layering violation unnoticed (#585).

The layering tests (#560) pass every violation recorded under a module's
`src/test/resources/archunit-store`. Their failure message gives the command that re-records a
store, and run on new code that command freezes the new violation. This check compares the
stores of the pull request with those of its base, so the stores can only shrink:

- Violation lines are counted per rule (the key in `stored.rules`), without their source line
  number: `in (File.java:84)` is compared as `in (File.java)`, because a re-record writes today's
  line numbers. A line's count on the head may be at most its count on the lines it is compared
  with.
- A key present on the base and the head is compared with that key's own base lines.
- The predecessor of an added key is the single removed key in that store whose properties
  filename equals the new key's filename. A second new key that reuses that filename has no
  predecessor. A new key whose filename another head key still uses has no predecessor. The new
  key passes when its normalized lines are a submultiset of the predecessor's. An added key with
  no predecessor is a new rule: every line it holds fails, even when that line is frozen under
  another rule. A new filename never inherits lines, including when the store's only key is
  removed and the head has exactly one new filename.
- Two head keys that name one store file fail. The message names both keys and says to drop the
  old key.
- A removed key with no added key passes. A dropped line passes. An added key with no predecessor
  lists, in sorted order, the store filenames of removed keys that no head key uses, and says: if
  this key rewords one of those rules, point it at that rule's file in stored.rules. When no such
  filename exists the line names none. When the head kept the store's one rule and added a key,
  the message names that kept file and says to point the new key at it and drop the kept key.
  When several rules stayed and none was removed, the message says the filename is new and to
  keep an existing stored.rules filename.

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
    """Lines the head adds, plus one line when two head keys name one store file."""
    base_tree, head_tree = Tree(repo, base), Tree(repo, head)

    def file_name(path: str) -> str:
        return path.rsplit("/", 1)[-1]

    def unpaired_hint(before: dict, removed: dict, unused: list[str]) -> str:
        # Pairing is by filename only. The hint tells the author which file to keep.
        if not removed and len(before) == 1:
            kept_file = file_name(next(iter(before.values()))[0])
            return f"Point the new key at {kept_file} and drop the kept key"
        if not removed and len(before) >= 2:
            return "The filename is new; keep an existing stored.rules filename"
        if removed:
            listed = ", ".join(unused) if unused else "none"
            return ("if this key rewords one of those rules, point it at that rule's file "
                    "in stored.rules: " + listed)
        return ""

    found = []
    for store in sorted(set(head_tree.stores())):
        module = store[: -len(STORE)].rstrip("/") or "."
        before = store_lines(base_tree, store)
        after = store_lines(head_tree, store)
        by_file: dict[str, list[str]] = {}
        for rule, (path, _lines) in after.items():
            by_file.setdefault(file_name(path), []).append(rule)
        for fname, rules in sorted(by_file.items()):
            if len(rules) < 2:
                continue
            named = " and ".join(sorted(rules))
            found.append(f"{module}: stored.rules keys {named} name one store file {fname}; "
                         "drop the old key")
        removed = {rule: before[rule] for rule in before if rule not in after}
        removed_by_file: dict[str, list[str]] = {}
        for rule, (path, _lines) in removed.items():
            removed_by_file.setdefault(file_name(path), []).append(rule)
        head_files = set(by_file)
        unused = sorted(file_name(path) for rule, (path, _lines) in removed.items()
                        if file_name(path) not in head_files)
        hint = unpaired_hint(before, removed, unused)

        def predecessor(rule_path: str) -> str | None:
            fname = file_name(rule_path)
            if len(by_file.get(fname, [])) != 1:
                return None
            candidates = removed_by_file.get(fname, [])
            if len(candidates) != 1:
                return None
            return candidates[0]

        for rule, (path, lines) in sorted(after.items()):
            if rule in before:
                allowed = Counter(normalized(line) for line in before[rule][1])
                extra = ""
            else:
                pred = predecessor(path)
                if pred is None:
                    allowed = Counter()
                    extra = f". {hint}" if hint else ""
                else:
                    allowed = Counter(normalized(line) for line in before[pred][1])
                    extra = ""
            for line in lines:
                key = normalized(line)
                if allowed[key] > 0:
                    allowed[key] -= 1
                else:
                    found.append(f"{module}: {path} adds a frozen violation: {line}{extra}")
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
                for rule, spec in rules.items():
                    if isinstance(spec, tuple):
                        filename, lines = spec
                    else:
                        filename, lines = f"id-{abs(hash(rule))}", spec
                    props.append(rule.replace(" ", "\\ ").replace(":", "\\:") + f"={filename}")
                    (store / filename).write_text("".join(l + "\n" for l in lines))
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
        kept_file = f"id-{abs(hash(rule))}"
        write({"java/svc": {reworded: [a.replace(":84", ":86"), b]}, "sms": {}})
        lines = added_lines(repo, base)
        reword_hint = ("if this key rewords one of those rules, point it at that rule's file "
                       "in stored.rules")
        checks.append(("a one-rule store re-recorded onto a new filename fails on every line",
                       len(lines) == 2 and all(line.endswith(kept_file) and reword_hint in line
                                               for line in lines)))
        write({"java/svc": {reworded: (kept_file, [a.replace(":84", ":86"), b.replace(":90", ":12")])},
               "sms": {}})
        checks.append(("a same-filename rename passes", added_lines(repo, base) == []))
        write({"java/svc": {reworded: (kept_file, [a])}, "sms": {}})
        checks.append(("a dropped line passes", added_lines(repo, base) == []))
        write({"java/svc": {reworded: (kept_file, [a, a])}, "sms": {}})
        checks.append(("a second copy of a predecessor line fails",
                       len(added_lines(repo, base)) == 1))
        write({"java/svc": {reworded: (kept_file, [a, b, "Method <a.N.e()> calls x in (N.java:1)"])},
               "sms": {}})
        lines = added_lines(repo, base)
        checks.append(("the same filename with a head file that adds a line fails",
                       len(lines) == 1 and lines[0].endswith("in (N.java:1)")))
        write({"java/svc": {rule: [a, b], reworded: []}, "sms": {}})
        checks.append(("a kept old key beside a new key with its own empty file passes",
                       added_lines(repo, base) == []))
        write({"java/svc": {rule: [a, b], reworded: [a.replace(":84", ":86"), b]}, "sms": {}})
        lines = added_lines(repo, base)
        checks.append(("a reworded rule next to its kept old key fails from added_lines",
                       len(lines) == 2 and all(
                           f"Point the new key at {kept_file} and drop the kept key" in line
                           for line in lines)))
        write({"java/svc": {reworded: [a, b, "Method <a.N.e()> calls x in (N.java:1)"]}, "sms": {}})
        lines = added_lines(repo, base)
        checks.append(("a reworded rule with a new violation fails on every line",
                       len(lines) == 3 and any("in (N.java:1)" in line for line in lines)
                       and all(line.endswith(kept_file) for line in lines)))

        write({"java/svc": {rule: [a, b]}, "sms": {"no classes should x": ["Method <s.T.u()> calls x in (T.java:5)"]}})
        lines = added_lines(repo, base)
        checks.append(("a module outside java/ is checked", len(lines) == 1 and lines[0].startswith("sms: ")))

        write({"java/svc": {rule: [a, b]}, "sms": {}, "mqtt": {"no classes should x": ["Method calls x in (M.java:2)"]}})
        lines = added_lines(repo, base)
        checks.append(("a new store counts every line", len(lines) == 1 and lines[0].startswith("mqtt: ")))

        checks.append(("a rule key is read with properties escapes", parse_properties(
            "#c\na\\ b\\:\\ c\\=d=id-1\n") == {"a b: c=d": "id-1"}))

        empty_a, empty_b = "empty alpha", "empty beta"
        write({"java/svc": {empty_a: ("same-empty", []), empty_b: ("same-empty", [])}, "sms": {}})
        lines = added_lines(repo, base)
        said: list[str] = []
        empty_rc = check(repo, base, lambda: [], say=said.append)
        checks.append(("two head keys that name one empty store file fail",
                       len(lines) == 1 and empty_a in lines[0] and empty_b in lines[0]
                       and "drop the old key" in lines[0] and empty_rc == 1
                       and any(s.startswith("::error::") and empty_a in s and empty_b in s
                               for s in said)))

        shared_a, shared_b = "shared alpha", "shared beta"
        write({"java/svc": {shared_a: ("same-file", [a]), shared_b: ("same-file", [a])}, "sms": {}})
        lines = added_lines(repo, base)
        structural = [line for line in lines if "name one store file" in line]
        violations = [line for line in lines if "adds a frozen violation" in line]
        checks.append(("two head keys naming one store file fail",
                       len(structural) == 1 and shared_a in structural[0] and shared_b in structural[0]
                       and "drop the old key" in structural[0] and len(violations) == 2))

        own_file = f"id-{abs(hash(rule))}"
        write({"java/svc": {"new one": (own_file, [a, b]), "new two": (own_file, [a, b])}, "sms": {}})
        lines = added_lines(repo, base)
        structural = [line for line in lines if "name one store file" in line]
        violations = [line for line in lines if "adds a frozen violation" in line]
        checks.append(("two new keys sharing one removed filename fail",
                       len(structural) == 1 and "new one" in structural[0] and "new two" in structural[0]
                       and "drop the old key" in structural[0] and len(violations) == 4))

        write({"java/svc": {}, "sms": {}})
        checks.append(("a removed key with no added key passes", added_lines(repo, base) == []))

        rule_b = "no classes should depend on 'jakarta.ws.rs..', because: other"
        rule_c = "no classes should depend on 'jakarta.ws.rs..', because: third"
        c = "Method <a.C.c()> calls method <jakarta.ws.rs.X.c()> in (C.java:7)"
        third = "Method <a.E.e()> calls method <jakarta.ws.rs.X.e()> in (E.java:8)"
        file_a, file_b, file_c = "file-a", "file-b", "file-c"
        write({"java/svc": {rule: (file_a, [a, b]), rule_b: (file_b, [c]), rule_c: (file_c, [third])},
               "sms": {}})
        base_many = commit()
        renamed_a = rule.replace("edge", "edge; renamed")
        renamed_b = rule_b.replace("other", "other; renamed")

        def many(rules: dict):
            write({"java/svc": rules, "sms": {}})
            return added_lines(repo, base_many)

        checks.append(("a new key with an empty file beside unchanged keys passes", many({
            rule: (file_a, [a, b]), rule_b: (file_b, [c]), rule_c: (file_c, [third]),
            "empty addition": ("id-empty", []),
        }) == []))
        checks.append(("two renames that keep their filenames pass", many({
            renamed_a: (file_a, [a, b]), renamed_b: (file_b, [c]), rule_c: (file_c, [third]),
        }) == []))
        checks.append(("two same-filename renames in one module pass", many({
            renamed_a: (file_a, [a.replace(":84", ":86"), b]),
            renamed_b: (file_b, [c.replace(":7", ":11")]),
            rule_c: (file_c, [third]),
        }) == []))
        checks.append(("two same-filename renames with the line multisets swapped fail",
                       len(many({
                           renamed_a: (file_a, [c]), renamed_b: (file_b, [a, b]), rule_c: (file_c, [third]),
                       })) == 3))
        moved = many({renamed_a: (file_a, [a]), renamed_b: (file_b, [c, b]), rule_c: (file_c, [third])})
        checks.append(("one line moved from one renamed rule onto the other fails",
                       len(moved) == 1 and "(B.java:90)" in moved[0]))
        checks.append(("a line from a different rule's file fails",
                       len(many({renamed_a: (file_a, [c]), rule_b: (file_b, [c]), rule_c: (file_c, [third])})) == 1))
        checks.append(("either of two same-filename renames adding a line fails",
                       len(many({
                           renamed_a: (file_a, [a, b]),
                           renamed_b: (file_b, [c, "Method <a.N.e()> calls x in (N.java:9)"]),
                           rule_c: (file_c, [third]),
                       })) == 1))
        checks.append(("two same-UUID renames with different lines beside a third unchanged rule pass",
                       many({
                           renamed_a: (file_a, [a, b]), renamed_b: (file_b, [c]), rule_c: (file_c, [third]),
                       }) == []))
        fresh = "no classes should depend on 'jakarta.ws.rs..', because: fresh"
        copied = many({rule_b: (file_b, [c]), rule_c: (file_c, [third]), fresh: ("id-fresh", [a, b])})
        checks.append(("deleting rule A and adding rule B on a new UUID with A's lines fails",
                       len(copied) == 2 and all(reword_hint in line and line.endswith("file-a")
                                                for line in copied)
                       and all("file-b" not in line and "file-c" not in line for line in copied)))
        leftover = many({rule: (file_a, [a, b]), rule_c: (file_c, [third]),
                         reworded: ("id-left", [a, b])})
        checks.append(("a leftover key fails while another key was removed",
                       len(leftover) == 2 and all(reword_hint in line and line.endswith("file-b")
                                                  for line in leftover)))
        union = many({rule_c: (file_c, [third]), fresh: ("id-union", [a, b])})
        checks.append(("one new key whose lines are the union of two removed keys fails",
                       len(union) == 2 and all(reword_hint in line and "file-a, file-b" in line
                                               for line in union)
                       and all("file-b, file-a" not in line for line in union)))
        overlap = many({rule: (file_a, [a, b]), rule_b: (file_b, [c]), rule_c: (file_c, [third]),
                        reworded: ("id-overlap", [a, b])})
        kept_phrase = "The filename is new; keep an existing stored.rules filename"
        checks.append(("several kept keys and a new filename name no overlap file",
                       len(overlap) == 2 and all(kept_phrase in line for line in overlap)
                       and all("file-a" not in line and "file-b" not in line and "file-c" not in line
                               for line in overlap)))
        brand = "Method <z.Z.z()> calls x in (Z.java:1)"
        named_none = many({renamed_a: (file_a, [a, b]), rule_b: (file_b, [c]), rule_c: (file_c, [third]),
                           fresh: ("id-brand", [brand])})
        checks.append(("an added key names none when every removed filename is still in use",
                       len(named_none) == 1 and named_none[0].endswith(": none")
                       and "file-a" not in named_none[0] and reword_hint in named_none[0]))
        still_used = many({rule: (file_a, [c]), "copy of b": (file_a, [c]), rule_c: (file_c, [third])})
        still_structural = [line for line in still_used if "name one store file" in line]
        still_violations = [line for line in still_used if "adds a frozen violation" in line]
        checks.append(("a new key whose filename is still used by a head key fails",
                       len(still_structural) == 1 and "drop the old key" in still_structural[0]
                       and rule in still_structural[0] and "copy of b" in still_structural[0]
                       and len(still_violations) == 2
                       and all("(C.java:7)" in line for line in still_violations)))

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
