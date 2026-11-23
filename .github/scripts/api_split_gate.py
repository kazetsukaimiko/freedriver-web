#!/usr/bin/env python3
"""A pull request that changes an API module and other main sources fails (#748).

API modules match `**/*-api/**`, `**/*-model/**`, or `**/*-contract/**`. Other main
sources match `**/src/main/**` and none of those three, with predicate quantifier
`every`: an API module's own `src/main` stays on the API side. Frontend and resource
files under `src/main` are other main sources. A renamed file counts on both its
current path and its `previous_filename`, so a move across the boundary is mixed.

The label `api-split-override` passes a mixed pull request only when it is present
and the latest `labeled` event for it is by kazetsukaimiko. A later `unlabeled`
clears it. The pass message says the label is why it passed. An unreadable file
list or, on a mixed pull request, an unreadable event history fails the gate.
When the head SHA changes between the read and the post, live mode posts nothing.

Usage:
  python3 .github/scripts/api_split_gate.py --self-test
  python3 .github/scripts/api_split_gate.py
      live mode reads REPO, PR_NUMBER and GITHUB_TOKEN
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

LABEL = "api-split-override"
LABEL_OWNER = "kazetsukaimiko"
STATUS_CONTEXT = "api-split"
API_PATTERNS = ("**/*-api/**", "**/*-model/**", "**/*-contract/**")
OTHER_PATTERNS = ("**/src/main/**", "!**/*-api/**", "!**/*-model/**", "!**/*-contract/**")
PAGE_SIZE = 100
# The pulls files API stops at 3000 entries. One more full page means the list was cut.
MAX_PAGES = 30

MSG_API = "API modules only"
MSG_OTHER = "other main sources only"
MSG_NEITHER = "no API module and no other main source"
MSG_MIXED = "changes an API module and other main sources"
MSG_LABEL = "passed because of the api-split-override label"
MSG_FILES = "could not read the changed-file list"
MSG_EVENTS = "could not read the label history"


class ApiError(Exception):
    """The GitHub read failed or came back truncated."""


def glob_match(path: str, pattern: str) -> bool:
    """Picomatch-style match. `*` stays inside one segment. `**` spans segments, including none."""
    segs = [s for s in path.split("/") if s]
    pats = [s for s in pattern.split("/") if s]
    return _match(segs, pats)


def _match(segs: list[str], pats: list[str]) -> bool:
    if not pats:
        return not segs
    if pats[0] == "**":
        if _match(segs, pats[1:]):
            return True
        return bool(segs) and _match(segs[1:], pats)
    if not segs:
        return False
    return _segment(segs[0], pats[0]) and _match(segs[1:], pats[1:])


def _segment(text: str, pattern: str) -> bool:
    i = j = 0
    star = mark = -1
    while i < len(text):
        if j < len(pattern) and (pattern[j] == "?" or pattern[j] == text[i]):
            i += 1
            j += 1
        elif j < len(pattern) and pattern[j] == "*":
            star, mark = j, i
            j += 1
        elif star != -1:
            mark += 1
            i, j = mark, star + 1
        else:
            return False
    while j < len(pattern) and pattern[j] == "*":
        j += 1
    return j == len(pattern)


def _one_pattern(path: str, pattern: str) -> bool:
    negated = pattern.startswith("!")
    body = pattern[1:] if negated else pattern
    hit = glob_match(path, body)
    return not hit if negated else hit


def matches(path: str, patterns: tuple[str, ...], quantifier: str) -> bool:
    hits = [_one_pattern(path, pattern) for pattern in patterns]
    return all(hits) if quantifier == "every" else any(hits)


def is_api(path: str) -> bool:
    return matches(path, API_PATTERNS, "some")


def is_other_main(path: str) -> bool:
    return matches(path, OTHER_PATTERNS, "every")


def classes(files: list[dict]) -> tuple[bool, bool]:
    """Whether the changed paths touch an API module and whether they touch other main sources."""
    api = other = False
    for entry in files:
        names = [entry[key] for key in ("filename", "previous_filename") if entry.get(key)]
        for name in names:
            if is_api(name):
                api = True
            elif is_other_main(name):
                other = True
    return api, other


def override_honoured(events: list[dict]) -> tuple[bool, str]:
    """The label is on, and the event that left it on was LABEL_OWNER's."""
    on, actor = False, ""
    for event in events:
        if (event.get("label") or {}).get("name") != LABEL:
            continue
        kind = event.get("event")
        if kind == "labeled":
            on, actor = True, (event.get("actor") or {}).get("login", "")
        elif kind == "unlabeled":
            on, actor = False, ""
    return on and actor == LABEL_OWNER, actor


def decide(files: list[dict] | None, events: list[dict] | None) -> tuple[str, str]:
    """Commit-status state and description. `None` means that read failed."""
    if files is None:
        return "failure", MSG_FILES
    api, other = classes(files)
    if not (api and other):
        if api:
            return "success", MSG_API
        if other:
            return "success", MSG_OTHER
        return "success", MSG_NEITHER
    if events is None:
        return "failure", MSG_EVENTS
    honoured, actor = override_honoured(events)
    if honoured:
        return "success", MSG_LABEL
    if actor and actor != LABEL_OWNER:
        return "failure", f"{LABEL} was added by {actor}, not {LABEL_OWNER}"
    return "failure", MSG_MIXED


def apply(head_before: str, head_after: str, files, events, post) -> str:
    """Post on `head_after` when it is still `head_before`. Returns `posted` or `skipped`."""
    state, description = decide(files, events)
    if head_before != head_after:
        return "skipped"
    post(head_after, state, description)
    return "posted"


def read_pages(fetch_page, what: str) -> list:
    """Every page of a list endpoint. A short page ends the list. A read that runs past the cap fails."""
    items: list = []
    page = 1
    while page <= MAX_PAGES:
        batch = fetch_page(page)
        if not isinstance(batch, list):
            raise ApiError(f"{what} was not a list")
        items.extend(batch)
        if len(batch) < PAGE_SIZE:
            return items
        page += 1
    raise ApiError(f"{what} was truncated")


def github_json(method: str, url: str, token: str, body: dict | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "freedriver-api-split",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raise ApiError(f"GitHub API {exc.code} on {method} {url.split('?', 1)[0]}") from exc
    except urllib.error.URLError as exc:
        raise ApiError(f"GitHub API unreachable on {method} {url.split('?', 1)[0]}") from exc


def live() -> int:
    repo = os.environ.get("REPO", "")
    number = os.environ.get("PR_NUMBER", "")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not repo or not number or not token:
        print("REPO, PR_NUMBER and GITHUB_TOKEN are required", file=sys.stderr)
        return 2
    base = f"https://api.github.com/repos/{repo}"

    def fetch(method: str, url: str, body: dict | None = None):
        return github_json(method, url, token, body)

    try:
        head_before = fetch("GET", f"{base}/pulls/{number}")["head"]["sha"]
    except (ApiError, KeyError, TypeError) as exc:
        print(f"could not read the pull request head: {exc}", file=sys.stderr)
        return 1

    try:
        files = read_pages(
            lambda page: fetch("GET", f"{base}/pulls/{number}/files?per_page={PAGE_SIZE}&page={page}"),
            "changed-file list",
        )
    except ApiError as exc:
        print(exc, file=sys.stderr)
        files = None

    events = None
    api, other = classes(files) if files is not None else (False, False)
    if api and other:
        try:
            events = read_pages(
                lambda page: fetch(
                    "GET", f"{base}/issues/{number}/events?per_page={PAGE_SIZE}&page={page}"
                ),
                "label history",
            )
        except ApiError as exc:
            print(exc, file=sys.stderr)
            events = None

    try:
        head_after = fetch("GET", f"{base}/pulls/{number}")["head"]["sha"]
    except (ApiError, KeyError, TypeError) as exc:
        print(f"could not re-read the pull request head: {exc}", file=sys.stderr)
        return 1

    def post(sha: str, state: str, description: str) -> None:
        fetch(
            "POST",
            f"{base}/statuses/{sha}",
            {"state": state, "context": STATUS_CONTEXT, "description": description[:140]},
        )
        print(f"posted {state} on {sha}: {description}")

    try:
        outcome = apply(head_before, head_after, files, events, post)
    except ApiError as exc:
        print(f"could not post the status: {exc}", file=sys.stderr)
        return 1
    if outcome == "skipped":
        print(f"head moved from {head_before} to {head_after}; posted nothing")
    return 0


def _file(path: str, previous: str | None = None) -> dict:
    entry = {"filename": path}
    if previous:
        entry["previous_filename"] = previous
        entry["status"] = "renamed"
    return entry


def _labeled(actor: str, event: str = "labeled") -> dict:
    return {"event": event, "label": {"name": LABEL}, "actor": {"login": actor}}


def self_test() -> int:
    failures: list[str] = []

    def check(name: str, got, want) -> None:
        if got != want:
            failures.append(f"{name}: got {got!r}, want {want!r}")
        else:
            print(f"ok {name}")

    api = "java/dashboard-api/src/main/java/io/freedriver/app/Thing.java"
    impl = "java/dashboard/src/main/java/io/freedriver/app/Thing.java"
    webui = "java/dashboard/src/main/webui/src/App.tsx"
    resources = "java/dashboard/src/main/resources/application.properties"
    api_pom = "java/sms-api/pom.xml"
    api_test = "java/sms-api/src/test/java/SmsTest.java"
    impl_test = "java/dashboard/src/test/java/DashTest.java"
    impl_pom = "java/dashboard/pom.xml"
    contract = "mqtt-contract/src/main/java/Contract.java"
    package_api = "java/dashboard/src/main/java/io/freedriver/app/api/Thing.java"
    workflow = ".github/workflows/ci.yml"
    docs = "README.md"

    check("api path", is_api(api), True)
    check("api pom", is_api(api_pom), True)
    check("api test", is_api(api_test), True)
    check("contract path", is_api(contract), True)
    check("package named api is not an API module", is_api(package_api), False)
    check("impl is not api", is_api(impl), False)
    check("webui is other main", is_other_main(webui), True)
    check("resources are other main", is_other_main(resources), True)
    check("impl src/main is other main", is_other_main(impl), True)
    check("api src/main is not other main", is_other_main(api), False)
    check("impl test is neither", (is_api(impl_test), is_other_main(impl_test)), (False, False))
    check("impl pom is neither", (is_api(impl_pom), is_other_main(impl_pom)), (False, False))
    check("workflow is neither", (is_api(workflow), is_other_main(workflow)), (False, False))
    check("docs are neither", (is_api(docs), is_other_main(docs)), (False, False))

    check("API-only passes", decide([_file(api), _file(api_pom)], None), ("success", MSG_API))
    check("implementation-only passes", decide([_file(impl), _file(webui)], None), ("success", MSG_OTHER))
    check("mixed fails", decide([_file(api), _file(impl)], []), ("failure", MSG_MIXED))
    check("workflow-only passes", decide([_file(workflow), _file(docs)], None), ("success", MSG_NEITHER))
    check(
        "rename from an API module to an implementation module fails",
        decide([_file(impl, api)], []),
        ("failure", MSG_MIXED),
    )
    check("label absent fails", decide([_file(api), _file(impl)], []), ("failure", MSG_MIXED))
    check(
        "latest labeled actor not kazetsukaimiko fails",
        decide([_file(api), _file(impl)], [_labeled("kazetsukaiyuni")]),
        ("failure", f"{LABEL} was added by kazetsukaiyuni, not {LABEL_OWNER}"),
    )
    check(
        "a later unlabeled fails",
        decide([_file(api), _file(impl)], [_labeled(LABEL_OWNER), _labeled(LABEL_OWNER, "unlabeled")]),
        ("failure", MSG_MIXED),
    )
    check(
        "kazetsukaimiko's label passes",
        decide([_file(api), _file(impl)], [_labeled(LABEL_OWNER)]),
        ("success", MSG_LABEL),
    )
    check("unreadable event history fails", decide([_file(api), _file(impl)], None), ("failure", MSG_EVENTS))
    check("unreadable file list fails", decide(None, []), ("failure", MSG_FILES))
    check(
        "another account's label after the owner's fails",
        decide(
            [_file(api), _file(impl)],
            [_labeled(LABEL_OWNER), _labeled(LABEL_OWNER, "unlabeled"), _labeled("kazetsukaiyuni")],
        )[0],
        "failure",
    )
    check(
        "owner label again after removal passes",
        decide(
            [_file(contract), _file(resources)],
            [_labeled("kazetsukaiyuni"), _labeled("kazetsukaiyuni", "unlabeled"), _labeled(LABEL_OWNER)],
        ),
        ("success", MSG_LABEL),
    )
    check(
        "a different label is ignored",
        decide(
            [_file(api), _file(impl)],
            [{"event": "labeled", "label": {"name": "manual-review"}, "actor": {"login": LABEL_OWNER}}],
        ),
        ("failure", MSG_MIXED),
    )

    posted: list[tuple[str, str, str]] = []

    def post(sha: str, state: str, description: str) -> None:
        posted.append((sha, state, description))

    check("same head posts", apply("abc", "abc", [_file(api)], None, post), "posted")
    check("posted status", posted, [("abc", "success", MSG_API)])
    posted.clear()
    check("moved head posts nothing", apply("abc", "def", [_file(api), _file(impl)], [], post), "skipped")
    check("nothing posted", posted, [])

    def pages(chunks: list[list[dict]]):
        def fetch(page: int):
            if page < 1 or page > len(chunks):
                return []
            return chunks[page - 1]
        return fetch

    full = [{"filename": f"doc{i}.md"} for i in range(PAGE_SIZE)]
    short = [{"filename": "README.md"}]
    check("pages join", len(read_pages(pages([full, short]), "changed-file list")), PAGE_SIZE + 1)
    try:
        read_pages(pages([full] * (MAX_PAGES + 1)), "changed-file list")
        failures.append("truncated file list: want ApiError")
    except ApiError:
        print("ok truncated file list fails")

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("api-split self-test passed")
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv == ["--self-test"]:
        return self_test()
    if argv:
        print("usage: api_split_gate.py [--self-test]", file=sys.stderr)
        return 2
    return live()


if __name__ == "__main__":
    sys.exit(main())
