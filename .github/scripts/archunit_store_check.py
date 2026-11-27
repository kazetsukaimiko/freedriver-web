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
because every agent account can add a label. The label also needs one or more body lines
`Freeze-followup: <owner/repo>#<n>`, each naming an open issue in a kazetsukaimiko repository.
The check reads the pull request body and those issues when the job runs, so a re-run of the
failed `test` job sees a line added after the run started. It says when it passes on the label
and names each follow-up issue. ArchUnit stays with the layering tests.

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
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

STORE = "src/test/resources/archunit-store"
RULES = "stored.rules"
LABEL = "freeze-new-violation"
LABEL_OWNER = "kazetsukaimiko"
FOLLOWUP_FORMAT = "Freeze-followup: <owner/repo>#<n>"
FOLLOWUP_LINE = re.compile(r"^Freeze-followup:\s*(.*)$")
FOLLOWUP_REF = re.compile(r"^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)#([1-9]\d*)$")
LINE_NUMBER = re.compile(r"\(([^()\s:]+\.java):\d+\)")
GITHUB_HEADERS = {
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}


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


class IssueLookupError(Exception):
    """A follow-up issue request failed. status 404 is the missing issue; any other status is an error."""

    def __init__(self, status: int | None, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


def github_json(url: str, token: str):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", **GITHUB_HEADERS})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            detail = e.read().decode("utf-8", "replace")[:200]
        except Exception:
            detail = ""
        raise IssueLookupError(e.code, f"HTTP {e.code} {detail}".strip()) from e
    except urllib.error.URLError as e:
        raise IssueLookupError(None, str(e.reason)) from e


def issue_events(repo: str, number: str, token: str) -> list[dict]:
    events, page = [], 1
    while True:
        batch = github_json(
            f"https://api.github.com/repos/{repo}/issues/{number}/events?per_page=100&page={page}",
            token)
        events += batch
        if len(batch) < 100:
            return events
        page += 1


def pull_body(repo: str, number: str, token: str) -> str:
    """The pull request body as it is now. A re-run reads a line added after the workflow started."""
    data = github_json(f"https://api.github.com/repos/{repo}/pulls/{number}", token)
    return data.get("body") or ""


def fetch_issue(owner: str, name: str, number: str, token: str) -> dict:
    return github_json(f"https://api.github.com/repos/{owner}/{name}/issues/{number}", token)


def followup_refs(body: str) -> tuple[bool, list[tuple[str, str, str]], list[str]]:
    """Freeze-followup lines. Returns (present, refs, tokens that are not owner/repo#n)."""
    present = False
    refs: list[tuple[str, str, str]] = []
    bad: list[str] = []
    for raw in (body or "").splitlines():
        match = FOLLOWUP_LINE.match(raw.strip())
        if not match:
            continue
        present = True
        tokens = [token.strip() for token in re.split(r"[\s,]+", match.group(1).strip()) if token.strip()]
        if not tokens:
            bad.append(raw.strip())
            continue
        for token in tokens:
            parsed = FOLLOWUP_REF.match(token)
            if parsed:
                refs.append((parsed.group(1), parsed.group(2), parsed.group(3)))
            else:
                bad.append(token)
    return present, refs, bad


def _repo_name(issue: dict) -> tuple[str, str]:
    parts = (issue.get("repository_url") or "").rstrip("/").split("/")
    if len(parts) < 2:
        return "", ""
    return parts[-2], parts[-1]


def followup_failures(refs: list[tuple[str, str, str]], bad: list[str], issue_of) -> tuple[list[str], list[str]]:
    """Failure messages for the named follow-ups, and the canonical names of the ones that pass."""
    failures = [f"{token} is not `{FOLLOWUP_FORMAT}`" for token in bad]
    accepted: list[str] = []
    for owner, name, number in refs:
        ref = f"{owner}/{name}#{number}"
        try:
            issue = issue_of(owner, name, number)
        except IssueLookupError as e:
            if e.status == 404:
                failures.append(
                    f"{ref} is missing. The follow-up must be an issue the job's token can read, "
                    "in a public kazetsukaimiko repository (lonewatt-ops is private and reads as missing).")
            else:
                failures.append(f"{ref}: the lookup failed: {e.detail}")
            continue
        except Exception as e:
            failures.append(f"{ref}: the lookup failed: {e}")
            continue
        if not isinstance(issue, dict):
            failures.append(f"{ref}: the lookup failed: the issue response was empty")
            continue
        if "pull_request" in issue:
            failures.append(f"{ref} is a pull request, not an issue")
            continue
        live_owner, live_name = _repo_name(issue)
        if live_owner.casefold() != LABEL_OWNER.casefold() or not live_name:
            failures.append(f"{ref} is outside kazetsukaimiko")
            continue
        if issue.get("state") != "open":
            failures.append(f"{ref} is closed")
            continue
        live_number = issue.get("number", number)
        accepted.append(f"{live_owner}/{live_name}#{live_number}")
    return failures, accepted


def check(repo: Path, base: str, events_of=None, say=print, body_of=None, issue_of=None) -> int:
    lines = added_lines(repo, base)
    if not lines:
        say("no store under archunit-store gains a frozen violation")
        return 0
    events = events_of() if events_of else []
    accepted, by = label_added_by_owner(events)
    if accepted:
        if body_of is None or issue_of is None:
            say("the follow-up could not be checked")
            return 1
        try:
            body = body_of() or ""
        except Exception as e:
            say(f"the follow-up could not be checked: {e}")
            return 1
        present, refs, bad = followup_refs(body)
        if not present:
            say(f"a follow-up issue is needed. Add a body line `{FOLLOWUP_FORMAT}`.")
            return 1
        failures, names = followup_failures(refs, bad, issue_of)
        if failures:
            for failure in failures:
                say(f"::error::{failure}")
            say(f"Name each open follow-up issue with `{FOLLOWUP_FORMAT}`.")
            return 1
        named = ", ".join(names)
        say(f"{len(lines)} new frozen violation(s), accepted: {LABEL_OWNER} added the {LABEL} label; "
            f"passed on the label with follow-up {named}")
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
        def open_issue(owner="kazetsukaimiko", name="freedriver-web", number=1, state="open", pull=False):
            issue = {
                "number": int(number),
                "state": state,
                "repository_url": f"https://api.github.com/repos/{owner}/{name}",
            }
            if pull:
                issue["pull_request"] = {"url": f"https://api.github.com/repos/{owner}/{name}/pulls/{number}"}
            return issue

        def lookup_of(answers: dict):
            def lookup(owner, name, number):
                key = (owner.casefold(), name, str(number))
                found = answers[key]
                if isinstance(found, Exception):
                    raise found
                return found
            return lookup

        owner_label = [lab("labeled", LABEL_OWNER)]
        open_body = "Freeze-followup: KazetsukaiMiko/freedriver-web#782\r\n"
        open_answers = {("kazetsukaimiko", "freedriver-web", "782"): open_issue(number=782)}
        said: list[str] = []
        passed = check(repo, base, lambda: owner_label, say=said.append,
                       body_of=lambda: open_body, issue_of=lookup_of(open_answers))
        checks.append(("the label from kazetsukaimiko passes",
                       passed == 0 and any("passed on the label" in line
                                           and "kazetsukaimiko/freedriver-web#782" in line
                                           for line in said)))

        needed: list[str] = []
        checks.append(("label without the line fails",
                       check(repo, base, lambda: owner_label, say=needed.append,
                             body_of=lambda: "stores grow\n", issue_of=lookup_of(open_answers)) == 1
                       and any("a follow-up issue is needed" in line and FOLLOWUP_FORMAT in line
                               for line in needed)))

        closed_said: list[str] = []
        closed_body = "Freeze-followup: kazetsukaimiko/freedriver-web#3\n"
        closed_answers = {("kazetsukaimiko", "freedriver-web", "3"): open_issue(number=3, state="closed")}
        checks.append(("a closed issue fails",
                       check(repo, base, lambda: owner_label, say=closed_said.append,
                             body_of=lambda: closed_body, issue_of=lookup_of(closed_answers)) == 1
                       and any("kazetsukaimiko/freedriver-web#3 is closed" in line for line in closed_said)))

        missing_said: list[str] = []
        missing_body = "Freeze-followup: kazetsukaimiko/lonewatt-ops#9\n"
        missing_answers = {("kazetsukaimiko", "lonewatt-ops", "9"): IssueLookupError(404, "HTTP 404")}
        checks.append(("a missing issue fails",
                       check(repo, base, lambda: owner_label, say=missing_said.append,
                             body_of=lambda: missing_body, issue_of=lookup_of(missing_answers)) == 1
                       and any("is missing" in line and "public kazetsukaimiko repository" in line
                               for line in missing_said)
                       and not any("the lookup failed" in line for line in missing_said)))

        outside_said: list[str] = []
        outside_body = "Freeze-followup: kazetsukaimiko/freedriver-web#4\n"
        outside_answers = {("kazetsukaimiko", "freedriver-web", "4"):
                           open_issue(owner="other-org", name="freedriver-web", number=4)}
        checks.append(("an issue outside kazetsukaimiko fails",
                       check(repo, base, lambda: owner_label, say=outside_said.append,
                             body_of=lambda: outside_body, issue_of=lookup_of(outside_answers)) == 1
                       and any("is outside kazetsukaimiko" in line for line in outside_said)))

        pr_said: list[str] = []
        pr_body = "Freeze-followup: kazetsukaimiko/freedriver-web#100\n"
        pr_answers = {("kazetsukaimiko", "freedriver-web", "100"):
                      open_issue(number=100, pull=True)}
        checks.append(("a follow-up naming another open pull request fails as a pull request",
                       check(repo, base, lambda: owner_label, say=pr_said.append,
                             body_of=lambda: pr_body, issue_of=lookup_of(pr_answers)) == 1
                       and any("#100 is a pull request, not an issue" in line for line in pr_said)))

        own_said: list[str] = []
        own_body = "Freeze-followup: kazetsukaimiko/freedriver-web#782\n"
        own_answers = {("kazetsukaimiko", "freedriver-web", "782"): open_issue(number=782, pull=True)}
        checks.append(("a follow-up naming the pull request's own number fails as a pull request",
                       check(repo, base, lambda: owner_label, say=own_said.append,
                             body_of=lambda: own_body, issue_of=lookup_of(own_answers)) == 1
                       and any("#782 is a pull request, not an issue" in line for line in own_said)))

        http_said: list[str] = []
        http_body = "Freeze-followup: kazetsukaimiko/freedriver-web#8\n"
        http_answers = {("kazetsukaimiko", "freedriver-web", "8"): IssueLookupError(503, "HTTP 503 unavailable")}
        checks.append(("a non-404 HTTP error on the issue lookup fails",
                       check(repo, base, lambda: owner_label, say=http_said.append,
                             body_of=lambda: http_body, issue_of=lookup_of(http_answers)) == 1
                       and any("the lookup failed" in line and "503" in line for line in http_said)
                       and not any("is missing" in line for line in http_said)))

        pair_said: list[str] = []
        pair_body = ("Freeze-followup: kazetsukaimiko/freedriver-web#1\n"
                     "Freeze-followup: kazetsukaimiko/autonomy#2\n")
        pair_answers = {
            ("kazetsukaimiko", "freedriver-web", "1"): open_issue(name="freedriver-web", number=1),
            ("kazetsukaimiko", "autonomy", "2"): open_issue(name="autonomy", number=2, state="closed"),
        }
        checks.append(("two follow-ups with one closed fail and name the closed one",
                       check(repo, base, lambda: owner_label, say=pair_said.append,
                             body_of=lambda: pair_body, issue_of=lookup_of(pair_answers)) == 1
                       and any("kazetsukaimiko/autonomy#2 is closed" in line for line in pair_said)
                       and not any("freedriver-web#1 is closed" in line for line in pair_said)))

        unwired: list[str] = []
        checks.append(("the label accepted with no body or issue lookup wired fails",
                       check(repo, base, lambda: owner_label, say=unwired.append) == 1
                       and any(line == "the follow-up could not be checked" for line in unwired)))

        skipped: list[str] = []

        def refuse(*_args):
            skipped.append("called")
            raise AssertionError("follow-up lookup")

        checks.append(("the check fails without the label",
                       check(repo, base, lambda: [other], say=quiet, body_of=refuse, issue_of=refuse) == 1
                       and skipped == []))
        checks.append(("the label from an agent account fails",
                       check(repo, base, lambda: [lab("labeled", "lonewatt-architect")], say=quiet,
                             body_of=refuse, issue_of=refuse) == 1
                       and skipped == []))
        checks.append(("a label removed after kazetsukaimiko added it fails",
                       check(repo, base, lambda: [lab("labeled", LABEL_OWNER),
                                                  lab("unlabeled", LABEL_OWNER)], say=quiet,
                             body_of=refuse, issue_of=refuse) == 1
                       and skipped == []))
        checks.append(("the latest adding counts", check(repo, base, lambda: [
            lab("labeled", LABEL_OWNER), lab("unlabeled", "kazetsukaiyuni"),
            lab("labeled", "kazetsukaiyuni")], say=quiet, body_of=refuse, issue_of=refuse) == 1
                       and skipped == []))

        write({"java/svc": {rule: [a]}, "sms": {}})
        shrink_calls: list[str] = []

        def shrink_refuse(*_args):
            shrink_calls.append("called")
            raise AssertionError("shrink lookup")

        checks.append(("a shrink-only store passes with neither label nor line",
                       check(repo, base, shrink_refuse, say=quiet, body_of=shrink_refuse,
                             issue_of=shrink_refuse) == 0
                       and shrink_calls == []))
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
    events_of = body_of = issue_of = None
    if gh_repo and number and token:
        events_of = lambda: issue_events(gh_repo, number, token)
        body_of = lambda: pull_body(gh_repo, number, token)
        issue_of = lambda owner, name, num: fetch_issue(owner, name, num, token)
    return check(repo, sys.argv[1], events_of, body_of=body_of, issue_of=issue_of)


if __name__ == "__main__":
    sys.exit(main())
