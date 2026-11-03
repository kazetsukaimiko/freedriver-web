#!/usr/bin/env python3
"""Mark a draft PR ready and request kaze when the required checks are green.

Skip keep-draft, a non-default base, a missing or red required check, a Grok
request-changes verdict on this head, or a did-not-run Grok review.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request

GITHUB_API = "https://api.github.com"
KEEP_DRAFT = "keep-draft"
REVIEWER = "kazetsukaimiko"
REQUIRED_CHECKS = (
    "test",
    "webui",
    "prod-image",
    "commitlint",
    "Security Review",
    "Principled Engineering Review",
)
DID_NOT_RUN = (
    "Security Review did not run",
    "Principled Engineering Review did not run",
)


def skip_reason(
    pr: dict,
    checks: list[dict],
    reviews: list[dict],
    head_sha: str,
    default_branch: str = "main",
) -> str | None:
    if not pr.get("draft"):
        return "not-draft"
    labels = {item.get("name") for item in pr.get("labels") or []}
    if KEEP_DRAFT in labels:
        return "keep-draft"
    base = ((pr.get("base") or {}).get("ref")) or ""
    if base != default_branch:
        return "stacked"
    latest = {}
    for run in checks:
        name = run.get("name")
        if name:
            latest[name] = run
    for name in REQUIRED_CHECKS:
        run = latest.get(name)
        if run is None:
            return f"missing:{name}"
        if run.get("status") != "completed":
            return f"pending:{name}"
        if run.get("conclusion") != "success":
            return f"red:{name}"
    on_head = [review for review in reviews if (review.get("commit_id") or "") == head_sha]
    if not on_head:
        return "missing-reviews"
    for review in on_head:
        body = review.get("body") or ""
        if any(marker in body for marker in DID_NOT_RUN):
            return "did-not-run"
        if re.search(r"(?im)^(?:\*\*)?verdict(?:\*\*)?:\s*`?request-changes`?\s*$", body):
            return "request-changes"
    return None


def die(message: str, code: int = 1) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        die(f"missing required environment variable {name}")
    return value


def http_json(url: str, token: str, payload: dict | None = None, method: str | None = None) -> object:
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        method=method or ("POST" if data is not None else "GET"),
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "pr-ready-when-green",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        die(f"HTTP {error.code} from {url}: {detail}")
    except urllib.error.URLError as error:
        die(f"request failed for {url}: {error}")
    raise AssertionError("unreachable")


def paginate(url: str, token: str) -> list:
    items: list = []
    next_url: str | None = url
    while next_url:
        req = urllib.request.Request(
            next_url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "User-Agent": "pr-ready-when-green",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                chunk = json.loads(resp.read().decode())
                if isinstance(chunk, dict) and "check_runs" in chunk:
                    items.extend(chunk["check_runs"])
                elif isinstance(chunk, list):
                    items.extend(chunk)
                else:
                    die(f"unexpected page from {next_url}")
                link = resp.headers.get("Link") or ""
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            die(f"HTTP {error.code} from {next_url}: {detail}")
        next_url = None
        for part in link.split(","):
            if 'rel="next"' in part:
                next_url = part[part.find("<") + 1 : part.find(">")]
    return items


def cmd_self_test() -> None:
    sha = "abc"
    green = [{"name": name, "status": "completed", "conclusion": "success"} for name in REQUIRED_CHECKS]
    reviews = [
        {"commit_id": sha, "body": "> Security Review.\n\nVerdict: approve-as-is\n"},
        {"commit_id": sha, "body": "> Principled Engineering Review.\n\nVerdict: approve-as-is\n"},
    ]
    pr = {"draft": True, "labels": [], "base": {"ref": "main"}}
    if skip_reason(pr, green, reviews, sha) is not None:
        die("self-test: green draft should be ready")
    if skip_reason({**pr, "draft": False}, green, reviews, sha) != "not-draft":
        die("self-test: non-draft")
    if skip_reason({**pr, "labels": [{"name": KEEP_DRAFT}]}, green, reviews, sha) != "keep-draft":
        die("self-test: keep-draft")
    if skip_reason({**pr, "base": {"ref": "yuni/37-metrics-model"}}, green, reviews, sha) != "stacked":
        die("self-test: stacked")
    if skip_reason(pr, green[:-1], reviews, sha) != "missing:Principled Engineering Review":
        die("self-test: missing check")
    pending = green[:-1] + [{"name": "Principled Engineering Review", "status": "in_progress", "conclusion": None}]
    if skip_reason(pr, pending, reviews, sha) != "pending:Principled Engineering Review":
        die("self-test: pending check")
    red = green[:-1] + [{"name": "Principled Engineering Review", "status": "completed", "conclusion": "failure"}]
    if skip_reason(pr, red, reviews, sha) != "red:Principled Engineering Review":
        die("self-test: red check")
    if skip_reason(pr, green, [], sha) != "missing-reviews":
        die("self-test: missing reviews")
    blocked = [
        reviews[0],
        {"commit_id": sha, "body": "Verdict: request-changes\n"},
    ]
    if skip_reason(pr, green, blocked, sha) != "request-changes":
        die("self-test: request-changes")
    stale = [
        {"commit_id": "old", "body": "Verdict: request-changes\n"},
        *reviews,
    ]
    if skip_reason(pr, green, stale, sha) is not None:
        die("self-test: stale request-changes must not block")
    missing_run = [{"commit_id": sha, "body": "> Security Review did not run.\n"}]
    if skip_reason(pr, green, missing_run, sha) != "did-not-run":
        die("self-test: did-not-run")
    print("self-test OK")


def find_pr(repo: str, sha: str, token: str) -> dict | None:
    pulls = http_json(f"{GITHUB_API}/repos/{repo}/commits/{sha}/pulls", token)
    if not isinstance(pulls, list):
        die(f"unexpected pulls payload for {sha}")
    for pr in pulls:
        head = ((pr.get("head") or {}).get("sha")) or ""
        if pr.get("state") == "open" and head == sha:
            return pr
    return None


def convert_ready(node_id: str, token: str) -> None:
    payload = {
        "query": (
            "mutation($id:ID!){convertPullRequestToReadyForReview(input:{pullRequestId:$id})"
            "{pullRequest{isDraft}}}"
        ),
        "variables": {"id": node_id},
    }
    body = http_json(f"{GITHUB_API}/graphql", token, payload)
    errors = (body or {}).get("errors") if isinstance(body, dict) else None
    if errors:
        die(f"convert to ready failed: {errors}")


def request_reviewer(repo: str, number: int, token: str) -> None:
    url = f"{GITHUB_API}/repos/{repo}/pulls/{number}/requested_reviewers"
    data = json.dumps({"reviewers": [REVIEWER]}).encode()
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "pr-ready-when-green",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            resp.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        if error.code in (422, 403) and REVIEWER in detail:
            print(json.dumps({"skip": "reviewer", "detail": detail[:200]}))
            return
        die(f"HTTP {error.code} from {url}: {detail}")


def cmd_run() -> None:
    token = require_env("GITHUB_TOKEN")
    repo = require_env("REPO")
    sha = require_env("SHA")
    event = os.environ.get("RUN_EVENT", "").strip()
    if event not in {"pull_request", "pull_request_target"}:
        print(json.dumps({"skip": "event", "event": event}))
        return
    pr = find_pr(repo, sha, token)
    if pr is None:
        print(json.dumps({"skip": "no-open-pr", "sha": sha}))
        return
    number = pr["number"]
    checks = paginate(f"{GITHUB_API}/repos/{repo}/commits/{sha}/check-runs?per_page=100", token)
    reviews = paginate(f"{GITHUB_API}/repos/{repo}/pulls/{number}/reviews?per_page=100", token)
    reason = skip_reason(pr, checks, reviews, sha)
    if reason:
        print(json.dumps({"skip": reason, "pr": number, "sha": sha}))
        return
    convert_ready(pr["node_id"], token)
    request_reviewer(repo, number, token)
    print(json.dumps({"ready": True, "pr": number, "sha": sha, "reviewer": REVIEWER}))


def main() -> None:
    if "--self-test" in sys.argv:
        cmd_self_test()
        return
    cmd_run()


if __name__ == "__main__":
    main()
