#!/usr/bin/env python3
"""Check that each commit in a pull request is authored under its author's identity.

The agent account list .github/agent-accounts.json maps each agent login to
{"commit_email": <email or null>}; the ticket phrase lint (#564) reads the same file. It
is read from the pull request's base commit, so a pull request is held to the list as
merged, and a change to the list takes effect once it is on main.

A pull request whose author is unlisted, or listed with a null or missing commit_email,
passes with a note (kazetsukaimiko, github-actions[bot], lonewatt-product,
lonewatt-security). Commits GitHub makes (committer
noreply@github.com, such as the "Update branch" merge) and commits authored by
github-actions[bot] are exempt. Everything else must have the mapped author email.

CI: python3 .github/scripts/commit_author_check.py
    (reads GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, PR_AUTHOR, BASE_SHA)
Local: python3 .github/scripts/commit_author_check.py --pr N [--table FILE]
Tests: python3 .github/scripts/commit_author_check.py --self-test
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request

GITHUB_API = "https://api.github.com"
TABLE_PATH = ".github/agent-accounts.json"
GITHUB_COMMITTER = "noreply@github.com"
ACTIONS_BOT = "github-actions[bot]"


def exempt(commit: dict) -> bool:
    """A commit GitHub made, or one authored by github-actions[bot]."""
    committer = ((commit.get("commit") or {}).get("committer") or {}).get("email", "")
    login = (commit.get("author") or {}).get("login", "")
    return committer.lower() == GITHUB_COMMITTER or login == ACTIONS_BOT


def check(pr_author: str, table: dict | None, commits: list[dict]) -> tuple[bool, list[str]]:
    """(ok, lines). Fails when a commit's author email differs from the author's row."""
    if table is None:
        return True, [f"{TABLE_PATH} is on the base branch only after this check ships; nothing to check"]
    entry = table.get(pr_author)
    expected = entry.get("commit_email") if isinstance(entry, dict) else None
    if not expected:
        return True, [f"{pr_author} has no commit_email in {TABLE_PATH}; commits are not checked"]
    wrong = []
    for c in commits:
        if exempt(c):
            continue
        email = ((c.get("commit") or {}).get("author") or {}).get("email", "")
        if email.lower() != expected.lower():
            wrong.append(f"commit {c.get('sha', '?')[:12]} is authored as {email or '<none>'}; "
                         f"pull requests by {pr_author} commit as {expected}")
    if wrong:
        return False, wrong + [f"Re-author those commits as {expected} (git commit --amend --reset-author "
                               f"with that identity, or a rebase) and push."]
    return True, [f"{len(commits)} commit(s) checked; every commit by {pr_author} is authored as {expected}"]


def _get(url: str, token: str):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json",
                                               "Authorization": f"Bearer {token}",
                                               "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def base_table(repo: str, base_sha: str, token: str) -> dict | None:
    try:
        data = _get(f"{GITHUB_API}/repos/{repo}/contents/{TABLE_PATH}?ref={base_sha}", token)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise
    return json.loads(base64.b64decode(data["content"]).decode())


def pr_commits(repo: str, number: int, token: str) -> list[dict]:
    out, page = [], 1
    while True:
        batch = _get(f"{GITHUB_API}/repos/{repo}/pulls/{number}/commits?per_page=100&page={page}", token)
        out.extend(batch)
        if len(batch) < 100:
            return out
        page += 1


def self_test() -> int:
    table = {"kazetsukaiyuni": {"commit_email": "kazetsukaiyuni@gmail.com"},
             "lonewatt-architect": {"commit_email": "architect@freedriver.io"},
             "lonewatt-techops": {"commit_email": "kazetsukaiyuni@gmail.com"},
             "lonewatt-security": {"commit_email": None},
             "lonewatt-product": {}}

    def commit(sha, author, committer=None, login=None):
        return {"sha": sha, "author": {"login": login} if login else None,
                "commit": {"author": {"email": author}, "committer": {"email": committer or author}}}

    cases = [
        ("every commit as mapped", "kazetsukaiyuni", table,
         [commit("a1", "kazetsukaiyuni@gmail.com"), commit("a2", "KazetsukaiYuni@gmail.com")], True),
        ("techops commits as Yuni", "lonewatt-techops", table, [commit("b1", "kazetsukaiyuni@gmail.com")], True),
        ("another account's email", "lonewatt-architect", table,
         [commit("c1", "architect@freedriver.io"), commit("c2bad", "kazetsukaiyuni@gmail.com")], False),
        ("author without a row", "kazetsukaimiko", table, [commit("d1", "someone@example.com")], True),
        ("security listed with a null commit_email", "lonewatt-security", table, [commit("d2", "x@example.com")], True),
        ("product listed with no commit_email field", "lonewatt-product", table, [commit("d3", "y@example.com")], True),
        ("GitHub's update-branch merge", "kazetsukaiyuni", table,
         [commit("e1", "someone@example.com", committer="noreply@github.com")], True),
        ("github-actions[bot] commit", "kazetsukaiyuni", table,
         [commit("e2", "41898282+github-actions[bot]@users.noreply.github.com", login=ACTIONS_BOT)], True),
        ("table not on base yet", "kazetsukaiyuni", None, [commit("f1", "x@example.com")], True),
    ]
    failed = 0
    for name, who, tbl, commits, want in cases:
        ok, lines = check(who, tbl, commits)
        if ok != want:
            failed += 1
            print(f"FAIL {name}: ok={ok} want={want} {lines}")
    ok, lines = check("lonewatt-architect", table, [commit("c2bad0000000", "kazetsukaiyuni@gmail.com")])
    message = " ".join(lines)
    for needle in ("c2bad0000000", "kazetsukaiyuni@gmail.com", "architect@freedriver.io"):
        if needle not in message:
            failed += 1
            print(f"FAIL failure message names {needle!r}: {message}")
    print(f"commit_author_check self-test: {len(cases) + 3 - failed}/{len(cases) + 3} passed")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--pr", type=int, help="local run: pull request number")
    parser.add_argument("--table", help="local run: read the table from this file instead of the base commit")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "kazetsukaimiko/freedriver-web")
    number = args.pr or int(os.environ["PR_NUMBER"])
    if args.pr:
        pr = _get(f"{GITHUB_API}/repos/{repo}/pulls/{number}", token)
        author, base_sha = pr["user"]["login"], pr["base"]["sha"]
    else:
        author, base_sha = os.environ["PR_AUTHOR"], os.environ["BASE_SHA"]
    table = json.load(open(args.table)) if args.table else base_table(repo, base_sha, token)
    ok, lines = check(author, table, pr_commits(repo, number, token))
    for line in lines:
        print(line if ok else f"::error::{line}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
