#!/usr/bin/env python3
"""Move a card's Status on Project 3 from its pull request's events (#563).

The card is the issue named in the pull request description's `Refs: #N` footer.

  event    a pull_request_target event (GITHUB_EVENT_PATH): draft -> In Progress, ready -> In Review,
           merged -> Done, or on a release-gated repository Dependent on Other Tickets
  ready    called by pr-ready-when-green in the same run, after it marks a draft ready
  release  for each card in Dependent on Other Tickets: Done once the production build stamp's
           release contains every merged pull request of the card

A card in Dependent on Other Tickets is moved by `release` only. A merge leaves the card where it
is while another open pull request names it, or while the issue carries the `more-prs` label.
`release` leaves a card that carries the `live-check` label.

GITHUB_TOKEN reads the repository; BOARD_TOKEN (a classic token with the `project` scope) writes
Project 3. Without BOARD_TOKEN every mode notes it and exits 0.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request

GITHUB_API = "https://api.github.com"
PROJECT_OWNER = "kazetsukaimiko"
PROJECT_NUMBER = 3
RELEASE_GATED = {"kazetsukaimiko/freedriver-web"}
BUILD_STAMP_URL = "https://app.freedriver.io/api/build"

IN_PROGRESS = "In Progress"
IN_REVIEW = "In Review"
DONE = "Done"
DEPENDENT = "Dependent on Other Tickets"
MORE_PRS = "more-prs"
LIVE_CHECK = "live-check"

_REFS = re.compile(r"^Refs: #(\d+)\s*$")
_SEMVER = re.compile(r"^\d+\.\d+\.\d+$")


# ---- decisions (pure) -------------------------------------------------------


def card_of(body: str | None) -> int | None:
    """The issue number in the description's last line, `Refs: #N`."""
    lines = [line for line in (body or "").splitlines() if line.strip()]
    if not lines:
        return None
    m = _REFS.match(lines[-1].strip())
    return int(m.group(1)) if m else None


def event_status(action: str, pr: dict, repo: str, card_status: str | None,
                 issue_labels: set, other_open: bool) -> tuple[str | None, str]:
    """(new Status or None, reason) for one pull request event."""
    if card_status is None:
        return None, "card-not-on-board"
    if card_status == DEPENDENT:
        return None, "card-waits-for-release"
    if action in ("opened", "reopened", "ready_for_review", "converted_to_draft"):
        want = IN_PROGRESS if pr.get("draft") else IN_REVIEW
        return (None, "unchanged") if want == card_status else (want, action)
    if action == "closed":
        if not pr.get("merged"):
            return None, "closed-unmerged"
        if other_open:
            return None, "another-open-pr"
        if MORE_PRS in issue_labels:
            return None, MORE_PRS
        return (DEPENDENT, "merged-awaits-release") if repo in RELEASE_GATED else (DONE, "merged")
    return None, f"action:{action}"


def ready_status(card_status: str | None) -> tuple[str | None, str]:
    """pr-ready-when-green marked the pull request ready."""
    if card_status is None:
        return None, "card-not-on-board"
    if card_status == DEPENDENT:
        return None, "card-waits-for-release"
    if card_status == IN_REVIEW:
        return None, "unchanged"
    return IN_REVIEW, "ready"


def release_tag(stamp: dict) -> str | None:
    """The release tag of the production build stamp, or None when it is no release."""
    version = str((stamp or {}).get("build") or "")
    return f"v{version}" if _SEMVER.match(version) else None


def release_status(issue_labels: set, merged_shas: list, open_prs: int,
                   contained) -> tuple[str | None, str]:
    """Done when every merged pull request of the card is in the deployed release."""
    if LIVE_CHECK in issue_labels:
        return None, LIVE_CHECK
    if MORE_PRS in issue_labels:
        return None, MORE_PRS
    if open_prs:
        return None, "another-open-pr"
    if not merged_shas:
        return None, "no-merged-pr"
    missing = [sha for sha in merged_shas if not contained(sha)]
    if missing:
        return None, "not-released:" + ",".join(sha[:7] for sha in missing)
    return DONE, "released"


# ---- GitHub I/O -------------------------------------------------------------


def _request(url: str, token: str, payload: dict | None = None, method: str | None = None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "board-status",
               "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method=method or ("POST" if data is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}, resp.headers.get("Link") or ""
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {error.code} from {url}: {detail[:300]}")


def get(url: str, token: str):
    return _request(url, token)[0]


def paginate(url: str, token: str) -> list:
    items, next_url = [], url
    while next_url:
        page, link = _request(next_url, token)
        items.extend(page if isinstance(page, list) else [])
        next_url = None
        for part in link.split(","):
            if 'rel="next"' in part:
                next_url = part[part.find("<") + 1: part.find(">")]
    return items


def graphql(query: str, token: str, **variables) -> dict:
    body = get_graphql(query, token, variables)
    if body.get("errors"):
        raise SystemExit(f"GraphQL errors: {body['errors']}")
    return body["data"]


def get_graphql(query: str, token: str, variables: dict) -> dict:
    return _request(f"{GITHUB_API}/graphql", token, {"query": query, "variables": variables})[0]


class Board:
    """Project 3 through BOARD_TOKEN: a card's Status, and setting it by option name."""

    def __init__(self, token: str):
        self.token = token
        data = graphql("""query($o:String!,$n:Int!){user(login:$o){projectV2(number:$n){id
            field(name:"Status"){... on ProjectV2SingleSelectField{id options{id name}}}}}}""",
                       token, o=PROJECT_OWNER, n=PROJECT_NUMBER)
        project = data["user"]["projectV2"]
        self.project_id = project["id"]
        self.field_id = project["field"]["id"]
        self.options = {o["name"]: o["id"] for o in project["field"]["options"]}

    def card(self, repo: str, number: int) -> tuple[str | None, str | None]:
        owner, name = repo.split("/")
        data = graphql("""query($o:String!,$r:String!,$n:Int!){repository(owner:$o,name:$r){issue(number:$n){
            projectItems(first:20){nodes{id project{id}
            fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}}}}}}}""",
                       self.token, o=owner, r=name, n=number)
        issue = (data.get("repository") or {}).get("issue")
        for node in ((issue or {}).get("projectItems") or {}).get("nodes") or []:
            if node["project"]["id"] == self.project_id:
                return node["id"], (node.get("fieldValueByName") or {}).get("name")
        return None, None

    def set(self, item_id: str, status: str) -> None:
        graphql("""mutation($p:ID!,$i:ID!,$f:ID!,$o:String!){updateProjectV2ItemFieldValue(input:
            {projectId:$p,itemId:$i,fieldId:$f,value:{singleSelectOptionId:$o}}){projectV2Item{id}}}""",
                self.token, p=self.project_id, i=item_id, f=self.field_id, o=self.options[status])

    def dependent_cards(self, repo: str) -> list[tuple[str, int]]:
        cards, cursor = [], None
        while True:
            data = graphql("""query($o:String!,$n:Int!,$c:String){user(login:$o){projectV2(number:$n){
                items(first:100,after:$c){pageInfo{hasNextPage endCursor} nodes{id
                fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}}
                content{... on Issue{number repository{nameWithOwner}}}}}}}}""",
                           self.token, o=PROJECT_OWNER, n=PROJECT_NUMBER, c=cursor)
            items = data["user"]["projectV2"]["items"]
            for node in items["nodes"]:
                content = node.get("content") or {}
                status = (node.get("fieldValueByName") or {}).get("name")
                if status == DEPENDENT and (content.get("repository") or {}).get("nameWithOwner") == repo:
                    cards.append((node["id"], content["number"]))
            if not items["pageInfo"]["hasNextPage"]:
                return cards
            cursor = items["pageInfo"]["endCursor"]


def issue_labels(repo: str, number: int, token: str) -> set:
    issue = get(f"{GITHUB_API}/repos/{repo}/issues/{number}", token)
    return {label.get("name") for label in issue.get("labels") or []}


def open_prs_naming(repo: str, card: int, token: str, exclude: int | None = None) -> int:
    pulls = paginate(f"{GITHUB_API}/repos/{repo}/pulls?state=open&per_page=100", token)
    return sum(1 for pr in pulls if pr.get("number") != exclude and card_of(pr.get("body")) == card)


def merged_prs_naming(repo: str, card: int, token: str) -> list[str]:
    """Merge commits of this repository's merged pull requests whose footer names the card."""
    events = paginate(f"{GITHUB_API}/repos/{repo}/issues/{card}/timeline?per_page=100", token)
    shas = []
    for event in events:
        source = (event.get("source") or {}).get("issue") or {}
        if event.get("event") != "cross-referenced" or "pull_request" not in source:
            continue
        if (source.get("repository") or {}).get("full_name") != repo or card_of(source.get("body")) != card:
            continue
        pr = get(f"{GITHUB_API}/repos/{repo}/pulls/{source['number']}", token)
        if pr.get("merged_at") and pr.get("merge_commit_sha"):
            shas.append(pr["merge_commit_sha"])
    return sorted(set(shas))


def build_stamp() -> dict:
    """The production build stamp, a public GET with no token."""
    req = urllib.request.Request(BUILD_STAMP_URL, headers={"Accept": "application/json",
                                                           "User-Agent": "board-status"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode() or "{}")


def contained_in(repo: str, tag: str, token: str):
    def contained(sha: str) -> bool:
        compare = get(f"{GITHUB_API}/repos/{repo}/compare/{sha}...{tag}", token)
        return compare.get("status") in ("ahead", "identical")
    return contained


# ---- modes ------------------------------------------------------------------


def note(**fields) -> None:
    print(json.dumps(fields))


def run_event(repo: str, token: str, board: Board) -> None:
    with open(os.environ["GITHUB_EVENT_PATH"]) as f:
        event = json.load(f)
    pr = event.get("pull_request") or {}
    card = card_of(pr.get("body"))
    if card is None:
        return note(skip="no-refs", pr=pr.get("number"))
    item, status = board.card(repo, card)
    action = event.get("action", "")
    other = open_prs_naming(repo, card, token, exclude=pr.get("number")) if action == "closed" else 0
    labels = issue_labels(repo, card, token) if action == "closed" else set()
    want, reason = event_status(action, pr, repo, status, labels, other > 0)
    if want:
        board.set(item, want)
    note(card=card, pr=pr.get("number"), was=status, now=want or status, reason=reason)


def run_ready(repo: str, number: int, token: str, board: Board) -> None:
    pr = get(f"{GITHUB_API}/repos/{repo}/pulls/{number}", token)
    card = card_of(pr.get("body"))
    if card is None:
        return note(skip="no-refs", pr=number)
    item, status = board.card(repo, card)
    want, reason = ready_status(status)
    if want:
        board.set(item, want)
    note(card=card, pr=number, was=status, now=want or status, reason=reason)


def run_release(repo: str, token: str, board: Board) -> None:
    stamp = build_stamp()
    tag = release_tag(stamp)
    if tag is None:
        return note(skip="stamp-is-no-release", stamp=stamp)
    contained = contained_in(repo, tag, token)
    for item, card in board.dependent_cards(repo):
        want, reason = release_status(issue_labels(repo, card, token), merged_prs_naming(repo, card, token),
                                      open_prs_naming(repo, card, token), contained)
        if want:
            board.set(item, want)
        note(card=card, release=tag, now=want or DEPENDENT, reason=reason)


def self_test() -> None:
    def check(got, want, label):
        if got != want:
            raise SystemExit(f"self-test {label}: {got!r} != {want!r}")

    gated, other_repo = "kazetsukaimiko/freedriver-web", "kazetsukaimiko/autonomy"
    check(card_of("Change.\n\nRefs: #563\n"), 563, "refs")
    check(card_of("Refs: #563\n\nMore text after."), None, "refs-not-last")
    check(card_of("Refs: #1, #2"), None, "refs-two")
    check(card_of(None), None, "refs-none")
    draft, ready = {"draft": True}, {"draft": False}
    check(event_status("opened", draft, gated, "Todo", set(), False), (IN_PROGRESS, "opened"), "draft")
    check(event_status("ready_for_review", ready, gated, IN_PROGRESS, set(), False), (IN_REVIEW, "ready_for_review"), "ready")
    check(event_status("opened", ready, gated, "Todo", set(), False), (IN_REVIEW, "opened"), "opened-ready")
    check(event_status("converted_to_draft", draft, gated, IN_REVIEW, set(), False), (IN_PROGRESS, "converted_to_draft"), "back-to-draft")
    check(event_status("opened", draft, gated, IN_PROGRESS, set(), False), (None, "unchanged"), "unchanged")
    merged = {"draft": False, "merged": True}
    check(event_status("closed", merged, other_repo, IN_REVIEW, set(), False), (DONE, "merged"), "merged")
    check(event_status("closed", merged, gated, IN_REVIEW, set(), False), (DEPENDENT, "merged-awaits-release"), "merged-gated")
    check(event_status("closed", {"merged": False}, gated, IN_REVIEW, set(), False), (None, "closed-unmerged"), "closed")
    check(event_status("closed", merged, gated, IN_REVIEW, set(), True), (None, "another-open-pr"), "second-pr")
    check(event_status("closed", merged, other_repo, IN_REVIEW, {MORE_PRS}, False), (None, MORE_PRS), "more-prs")
    for action in ("opened", "ready_for_review", "closed"):
        check(event_status(action, merged, gated, DEPENDENT, set(), False), (None, "card-waits-for-release"), f"dependent-{action}")
    check(event_status("opened", draft, gated, None, set(), False), (None, "card-not-on-board"), "off-board")
    check(ready_status(IN_PROGRESS), (IN_REVIEW, "ready"), "ready-path")
    check(ready_status(DEPENDENT), (None, "card-waits-for-release"), "ready-dependent")
    check(ready_status(None), (None, "card-not-on-board"), "ready-off-board")
    check(release_tag({"build": "1.6.0"}), "v1.6.0", "tag")
    check(release_tag({"build": "1.0.0-SNAPSHOT"}), None, "tag-snapshot")
    check(release_tag({}), None, "tag-empty")
    released = {"aaa", "bbb"}.__contains__
    check(release_status(set(), ["aaa", "bbb"], 0, released), (DONE, "released"), "released")
    check(release_status(set(), ["aaa", "ccc1234"], 0, released), (None, "not-released:ccc1234"), "not-released")
    check(release_status({LIVE_CHECK}, ["aaa"], 0, released), (None, LIVE_CHECK), "live-check")
    check(release_status({MORE_PRS}, ["aaa"], 0, released), (None, MORE_PRS), "release-more-prs")
    check(release_status(set(), ["aaa"], 1, released), (None, "another-open-pr"), "release-open-pr")
    check(release_status(set(), [], 0, released), (None, "no-merged-pr"), "release-none")
    print("self-test OK")


def main() -> None:
    if "--self-test" in sys.argv:
        self_test()
        return
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    board_token = os.environ.get("BOARD_TOKEN", "").strip()
    if not board_token:
        note(skip="no-board-token", mode=mode)
        return
    repo = os.environ["REPO"]
    token = os.environ["GITHUB_TOKEN"]
    board = Board(board_token)
    if mode == "event":
        run_event(repo, token, board)
    elif mode == "ready":
        run_ready(repo, int(sys.argv[2]), token, board)
    elif mode == "release":
        run_release(repo, token, board)
    else:
        raise SystemExit("usage: board_status.py event | ready <pr> | release | --self-test")


if __name__ == "__main__":
    main()
