#!/usr/bin/env python3
"""Keep the manual-review label in step with GitHub's request to kazetsukaimiko (#558).

The label is on from GitHub's review request to kazetsukaimiko until kazetsukaimiko has
approved the pull request's current head. It is on again when that approval is dismissed
or the head moves past it, and off when the request is removed with no review given.

The request decides, never a review alone. GitHub keeps every request on record in the pull
request's issue events (review_requested, review_request_removed); a submitted review consumes
the pending request but leaves no removal event. So the label is wanted while kazetsukaimiko has
a pending request, or while a request is on record with no later removal and kazetsukaimiko has
not approved the current head. With no request on record it stays off, whatever reviews exist.

It reads only the pull request, its review requests, its reviews and its issue events, and
writes only the label. It matches no CODEOWNERS pattern: GitHub's own request is the signal. It never
checks out or runs the pull request's code.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

GITHUB_API = "https://api.github.com"
LABEL = "manual-review"
OWNER = "kazetsukaimiko"


def request_on_record(events: list[dict], owner: str = OWNER) -> bool:
    """True when the issue events hold a review request to ``owner`` with no later removal."""
    state = False
    for event in events:
        kind = event.get("event")
        if kind not in ("review_requested", "review_request_removed"):
            continue
        if ((event.get("requested_reviewer") or {}).get("login") or "") != owner:
            continue
        state = kind == "review_requested"
    return state


def approved_head(reviews: list[dict], head_sha: str, owner: str = OWNER) -> bool:
    """True when ``owner``'s latest deciding review is an approval of ``head_sha``."""
    own = [r for r in reviews if ((r.get("user") or {}).get("login") or "") == owner]
    decisive = [r for r in own if r.get("state") in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED")]
    if not decisive:
        return False
    last = decisive[-1]
    return last.get("state") == "APPROVED" and (last.get("commit_id") or "") == head_sha


def label_wanted(requested: list[str], reviews: list[dict], head_sha: str, events: list[dict],
                 owner: str = OWNER) -> bool:
    """True while GitHub asks ``owner`` to review and ``owner`` has not approved ``head_sha``.

    ``requested``: logins with a pending review request. ``reviews``: the pull request's
    reviews in order, each with ``user.login``, ``state`` and ``commit_id``. ``events``: the
    pull request's issue events in order (review_requested / review_request_removed carry
    ``requested_reviewer.login``).
    """
    if owner in requested:
        return True
    return request_on_record(events, owner) and not approved_head(reviews, head_sha, owner)


def die(message: str, code: int = 1) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        die(f"missing required environment variable {name}")
    return value


def request(url: str, token: str, method: str = "GET", payload: dict | None = None) -> tuple[int, object, str]:
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "manual-review-label",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else {}), resp.headers.get("Link") or ""
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        return error.code, {"message": detail}, ""
    except urllib.error.URLError as error:
        die(f"request failed for {url}: {error}")
    raise AssertionError("unreachable")


def get(url: str, token: str) -> object:
    status, body, _ = request(url, token)
    if status != 200:
        die(f"HTTP {status} from {url}: {body}")
    return body


def all_pages(url: str, token: str) -> list:
    items: list = []
    next_url: str | None = url
    while next_url:
        status, body, link = request(next_url, token)
        if status != 200 or not isinstance(body, list):
            die(f"HTTP {status} from {next_url}: {body}")
        items.extend(body)
        next_url = None
        for part in link.split(","):
            if 'rel="next"' in part:
                next_url = part[part.find("<") + 1 : part.find(">")]
    return items


def requested_logins(repo: str, number: int, token: str) -> list[str]:
    body = get(f"{GITHUB_API}/repos/{repo}/pulls/{number}/requested_reviewers", token)
    return [u.get("login") or "" for u in (body or {}).get("users") or []]


def sync_label(repo: str, number: int, token: str, settle_tries: int = 1, settle_seconds: float = 3.0) -> dict:
    """Read the pull request's requests and reviews and set or remove the label once.

    ``settle_tries`` > 1 re-reads the requests a few times before deciding: right after a
    pull request is marked ready, GitHub adds code-owner requests a moment later.
    """
    pr = get(f"{GITHUB_API}/repos/{repo}/pulls/{number}", token)
    head_sha = ((pr.get("head") or {}).get("sha")) or ""
    have = LABEL in {label.get("name") for label in pr.get("labels") or []}
    reviews = all_pages(f"{GITHUB_API}/repos/{repo}/pulls/{number}/reviews?per_page=100", token)
    events = all_pages(f"{GITHUB_API}/repos/{repo}/issues/{number}/events?per_page=100", token)
    requested = requested_logins(repo, number, token)
    for _ in range(max(0, settle_tries - 1)):
        if OWNER in requested:
            break
        time.sleep(settle_seconds)
        requested = requested_logins(repo, number, token)
    want = label_wanted(requested, reviews, head_sha, events)
    if want and not have:
        status, body, _ = request(f"{GITHUB_API}/repos/{repo}/issues/{number}/labels", token, "POST",
                                  {"labels": [LABEL]})
        if status not in (200, 201):
            die(f"adding {LABEL} to #{number} failed: HTTP {status} {body}")
    elif have and not want:
        url = f"{GITHUB_API}/repos/{repo}/issues/{number}/labels/{urllib.parse.quote(LABEL)}"
        status, body, _ = request(url, token, "DELETE")
        if status not in (200, 204, 404):
            die(f"removing {LABEL} from #{number} failed: HTTP {status} {body}")
    return {"pr": number, "head": head_sha, "requested": OWNER in requested,
            "on_record": request_on_record(events), "label": want, "changed": want != have}


def cmd_self_test() -> None:
    head = "new"

    def review(state: str, sha: str = head, login: str = OWNER) -> dict:
        return {"user": {"login": login}, "state": state, "commit_id": sha}

    asked = [{"event": "review_requested", "requested_reviewer": {"login": OWNER}}]
    removed = asked + [{"event": "review_request_removed", "requested_reviewer": {"login": OWNER}}]
    other = [{"event": "review_requested", "requested_reviewer": {"login": "lonewatt-architect"}}]

    cases = [
        ("requested, no review", [OWNER], [], asked, True),
        ("no request, no review", [], [], [], False),
        ("another reviewer requested", ["lonewatt-architect"], [review("APPROVED", login="lonewatt-architect")],
         other, False),
        ("approved the current head", [], [review("APPROVED")], asked, False),
        ("approval dismissed", [], [review("APPROVED", sha="old"), review("DISMISSED", sha="old")], asked, True),
        ("approved an older head", [], [review("APPROVED", sha="old")], asked, True),
        ("changes requested", [], [review("CHANGES_REQUESTED")], asked, True),
        ("approved after changes requested", [], [review("CHANGES_REQUESTED"), review("APPROVED")], asked, False),
        ("approved then a comment", [], [review("APPROVED"), review("COMMENTED")], asked, False),
        ("requested again after removal", [], [], removed + asked, True),
        # The four cases from the Architect's review of #572.
        ("a comment review with no request ever", [], [review("COMMENTED")], [], False),
        ("an approval dismissed by a push with no request ever", [],
         [review("APPROVED", sha="old"), review("DISMISSED", sha="old")], [], False),
        ("requested, commented, not yet approved", [], [review("COMMENTED")], asked, True),
        ("requested then removed", [], [], removed, False),
    ]
    for name, requested, reviews, events, expected in cases:
        if label_wanted(requested, reviews, head, events) is not expected:
            die(f"self-test: {name}: expected {expected}")
    _self_test_sync()
    print("self-test OK")


def _self_test_sync() -> None:
    """sync_label against a fake GitHub: add, remove, leave alone, wait for a late request, and
    decide from the request on record rather than from reviews."""
    global request, time
    real_request, real_time = request, time

    class FakeTime:
        @staticmethod
        def sleep(_seconds: float) -> None:
            return None

    def fake(labels: list[str], requested_seq: list[list[str]], reviews: list[dict], events: list[dict] | None = None):
        calls: list[tuple[str, str]] = []
        seq = list(requested_seq)

        def handler(url: str, token: str, method: str = "GET", payload: dict | None = None):
            calls.append((method, url))
            if url.endswith("/requested_reviewers"):
                users = seq.pop(0) if len(seq) > 1 else seq[0]
                return 200, {"users": [{"login": u} for u in users]}, ""
            if "/reviews" in url:
                return 200, reviews, ""
            if "/events" in url:
                return 200, list(events or []), ""
            if method == "GET":
                return 200, {"head": {"sha": "h"}, "labels": [{"name": n} for n in labels]}, ""
            return (201 if method == "POST" else 204), {}, ""
        return handler, calls

    try:
        time = FakeTime  # type: ignore[assignment]
        request, calls = fake([], [[OWNER]], [])
        if not sync_label("o/r", 1, "t")["changed"] or ("POST", f"{GITHUB_API}/repos/o/r/issues/1/labels") not in calls:
            die("self-test: a request to the owner adds the label")
        approved = [{"user": {"login": OWNER}, "state": "APPROVED", "commit_id": "h"}]
        request, calls = fake([LABEL], [[]], approved)
        sync_label("o/r", 1, "t")
        if not any(m == "DELETE" and url.endswith(f"/labels/{LABEL}") for m, url in calls):
            die("self-test: approval of the current head removes the label")
        request, calls = fake([LABEL], [[OWNER]], [])
        sync_label("o/r", 1, "t")
        if any(m in ("POST", "DELETE") for m, _ in calls):
            die("self-test: a label already right is left alone")
        request, calls = fake([], [[], [], [OWNER]], [])
        if not sync_label("o/r", 1, "t", settle_tries=5)["label"]:
            die("self-test: a code-owner request that appears after marking ready is seen")
        asked = [{"event": "review_requested", "requested_reviewer": {"login": OWNER}}]
        commented = [{"user": {"login": OWNER}, "state": "COMMENTED", "commit_id": "h"}]
        request, calls = fake([], [[]], commented, asked)
        if not sync_label("o/r", 1, "t")["label"]:
            die("self-test: a request on record, consumed by a comment review, keeps the label on")
        request, calls = fake([LABEL], [[]], commented, [])
        sync_label("o/r", 1, "t")
        if not any(m == "DELETE" and url.endswith(f"/labels/{LABEL}") for m, url in calls):
            die("self-test: a review with no request on record removes the label")
    finally:
        request, time = real_request, real_time


def main() -> None:
    if "--self-test" in sys.argv:
        cmd_self_test()
        return
    token = require_env("GITHUB_TOKEN")
    repo = require_env("REPO")
    number = int(require_env("PR_NUMBER"))
    print(json.dumps(sync_label(repo, number, token)))


if __name__ == "__main__":
    main()
