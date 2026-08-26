#!/usr/bin/env python3
"""Advisory xAI PR reviewer: full files + one-level-out neighbors, not hunk-only.

The marketplace action tarmojussila/xai-code-review is hunk-only and must not be used.
This script expects a checkout of the PR head plus enough git history to diff against
the base. Findings are posted as a COMMENT review and never fail the job.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

PROMPT_PATH = Path(".github/grok-review-prompt.md")
XAI_URL = "https://api.x.ai/v1/chat/completions"
GITHUB_API = "https://api.github.com"
DEFAULT_MODEL = "grok-4"
MAX_FILE_BYTES = 256 * 1024
MAX_DIFF_CHARS = 80_000
MAX_PACK_CHARS = 220_000
MAX_REVIEW_CHARS = 65_000
SKIP_DIR_PARTS = {".git", "target", "node_modules", ".idea", "dist", ".cursor"}
SKIP_SUFFIXES = {
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".ico",
    ".jar",
    ".class",
    ".woff",
    ".woff2",
    ".exe",
    ".bin",
}
SKIP_NAMES = {
    "package-lock.json",
    "yarn.lock",
    "pnpm-lock.yaml",
    "mvnw",
    "mvnw.cmd",
}
LAYER_MARKERS = (
    "Resource",
    "Service",
    "Filter",
    "Mapper",
    "Backend",
    "Producer",
    "Exception",
)
ADVISORY_BANNER = (
    "> Advisory xAI review. Not a merge gate. Does not count as the required human review.\n\n"
)


def die(message: str, code: int = 1) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def run_git(args: list[str]) -> str:
    return subprocess.check_output(["git", *args], text=True)


def skipped(path: Path) -> bool:
    if any(part in SKIP_DIR_PARTS for part in path.parts):
        return True
    if path.name in SKIP_NAMES:
        return True
    if path.suffix.lower() in SKIP_SUFFIXES:
        return True
    return False


def repo_rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(Path.cwd().resolve()))
    except ValueError:
        return str(path)


def changed_name_status(base_sha: str, head_sha: str) -> list[tuple[str, str]]:
    raw = run_git(["diff", "--name-status", f"{base_sha}...{head_sha}"])
    rows: list[tuple[str, str]] = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        status, path = line.split("\t", 1)
        # Renames: R100\told\tnew — keep the new path.
        if status.startswith("R") and "\t" in path:
            path = path.split("\t", 1)[1]
        rows.append((status[0], path))
    return rows


def unified_diff(base_sha: str, head_sha: str) -> str:
    diff = run_git(["diff", "--unified=3", f"{base_sha}...{head_sha}"])
    if len(diff) > MAX_DIFF_CHARS:
        return diff[:MAX_DIFF_CHARS] + "\n\n[diff truncated]\n"
    return diff


def read_text_file(path: Path) -> str | None:
    if not path.is_file() or skipped(path):
        return None
    data = path.read_bytes()
    if len(data) > MAX_FILE_BYTES:
        return f"[skipped: {len(data)} bytes exceeds {MAX_FILE_BYTES}]\n"
    if b"\0" in data:
        return "[skipped: binary]\n"
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("utf-8", errors="replace")


def swap_main_test(path: Path) -> Path | None:
    text = str(path)
    if "/src/main/java/" in text:
        return Path(text.replace("/src/main/java/", "/src/test/java/", 1))
    if "/src/test/java/" in text:
        return Path(text.replace("/src/test/java/", "/src/main/java/", 1))
    return None


def same_dir_siblings(path: Path) -> list[Path]:
    parent = path.parent
    if not parent.is_dir():
        return []
    return [child for child in sorted(parent.iterdir()) if child.is_file() and not skipped(child)]


def test_twins(path: Path) -> list[Path]:
    twins: list[Path] = []
    swapped = swap_main_test(path)
    if swapped is None:
        return twins
    twins.append(swapped)
    if path.suffix == ".java":
        twins.append(swapped.with_name(f"{swapped.stem}Test.java"))
        twins.append(swapped.with_name(f"{swapped.stem}IT.java"))
        twins.append(swapped.with_name(f"{path.stem}ResourceTest.java"))
        twins.append(swapped.with_name(f"{path.stem}sResourceTest.java"))
    return twins


def layer_neighbors(changed: list[Path]) -> list[Path]:
    extra: list[Path] = []
    app_touched = any(repo_rel(path).startswith("app/") for path in changed)
    if app_touched:
        for root in (Path("app/src/main/resources"), Path("app/src/test/resources")):
            if not root.is_dir():
                continue
            for child in sorted(root.iterdir()):
                if child.is_file() and child.name.startswith("application"):
                    extra.append(child)
    packages: set[Path] = set()
    for path in changed:
        if path.suffix == ".java":
            packages.add(path.parent)
            twin = swap_main_test(path)
            if twin is not None:
                packages.add(twin.parent)
            # api <-> appliances pairing used by this repo
            if path.parent.name == "appliances":
                packages.add(path.parent.parent / "api")
                twin_pkg = swap_main_test(path.parent / "x.java")
                if twin_pkg is not None:
                    packages.add(twin_pkg.parent.parent / "api")
            if path.parent.name == "api":
                packages.add(path.parent.parent / "appliances")
                twin_pkg = swap_main_test(path.parent / "x.java")
                if twin_pkg is not None:
                    packages.add(twin_pkg.parent.parent / "appliances")
    for package in packages:
        if not package.is_dir():
            continue
        for child in sorted(package.iterdir()):
            if child.is_file() and child.suffix == ".java" and any(
                marker in child.stem for marker in LAYER_MARKERS
            ):
                extra.append(child)
    return extra


def collect_neighbors(changed_paths: list[str]) -> list[str]:
    changed = [Path(path) for path in changed_paths]
    found: set[str] = set()
    for path in changed:
        for sibling in same_dir_siblings(path):
            found.add(repo_rel(sibling))
        for twin in test_twins(path):
            if twin.is_file() and not skipped(twin):
                found.add(repo_rel(twin))
    for extra in layer_neighbors(changed):
        if extra.is_file() and not skipped(extra):
            found.add(repo_rel(extra))
    found -= set(changed_paths)
    return sorted(found)


def render_files(paths: list[str], heading: str) -> str:
    chunks: list[str] = [heading]
    for rel in paths:
        path = Path(rel)
        body = read_text_file(path)
        if body is None:
            chunks.append(f"\n===== {rel} (missing on PR head) =====\n")
            continue
        chunks.append(f"\n===== {rel} =====\n{body}")
        if not body.endswith("\n"):
            chunks.append("\n")
    return "".join(chunks)


def build_pack(
    *,
    title: str,
    body: str,
    base_sha: str,
    head_sha: str,
    changed: list[tuple[str, str]],
) -> str:
    changed_paths = [path for _status, path in changed]
    neighbors = collect_neighbors(changed_paths)
    existing_changed = [path for path in changed_paths if Path(path).is_file()]
    deleted = [path for status, path in changed if status == "D" or not Path(path).is_file()]

    listing = "\n".join(f"{status}\t{path}" for status, path in changed) or "(no files)"
    parts = [
        "You have a git checkout of the PR head and can treat the files below as the tree.\n",
        "The diff is the starting point, not the boundary. Review from the FULL files and neighbors.\n\n",
        f"PR title: {title}\n",
        f"PR body:\n{body or '(empty)'}\n\n",
        f"Base: {base_sha}\nHead: {head_sha}\n\n",
        "Changed files (git name-status):\n",
        listing,
        "\n",
        "\n===== UNIFIED DIFF (starting point) =====\n",
        unified_diff(base_sha, head_sha),
        "\n",
        render_files(existing_changed, "\n===== FULL CONTENTS OF TOUCHED FILES =====\n"),
    ]
    if deleted:
        parts.append("\n===== DELETED ON HEAD =====\n")
        parts.append("\n".join(deleted) + "\n")
    parts.append(
        render_files(
            neighbors,
            "\n===== ONE-LEVEL-OUT NEIGHBORS (resource / service / filter / mapper / application.properties / tests / same-dir siblings) =====\n",
        )
    )
    pack = "".join(parts)
    if len(pack) <= MAX_PACK_CHARS:
        return pack
    # Prefer touched files over neighbors if the pack is too large.
    parts = parts[:-1]
    parts.append(
        "\n===== ONE-LEVEL-OUT NEIGHBORS =====\n"
        "[truncated: pack exceeded size cap; neighbors omitted. Touched files above are still full.]\n"
    )
    pack = "".join(parts)
    if len(pack) > MAX_PACK_CHARS:
        pack = pack[:MAX_PACK_CHARS] + "\n\n[pack truncated]\n"
    return pack


def load_prompt() -> str:
    if not PROMPT_PATH.is_file():
        die(f"missing {PROMPT_PATH}")
    text = PROMPT_PATH.read_text()
    if "You are a principled engineer reviewing a GitHub pull request on freedriver-web." not in text:
        die(f"{PROMPT_PATH} is not the #64 lock (principled-engineer prompt missing)")
    if "Comment on the diff only" in text:
        die(f"{PROMPT_PATH} looks like the old paraphrase, not the #64 lock")
    return text


def http_json(url: str, *, token: str, payload: dict | None = None, timeout: int = 60) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
        "User-Agent": "freedriver-web-grok-review",
    }
    if payload is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method="GET" if data is None else "POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        die(f"HTTP {error.code} from {url}: {detail}")
    except urllib.error.URLError as error:
        die(f"request failed for {url}: {error}")
    raise AssertionError("unreachable")


def call_xai(system_prompt: str, user_pack: str, api_key: str, model: str) -> str:
    payload = {
        "model": model,
        "temperature": 0.2,
        "max_tokens": 8192,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_pack},
        ],
    }
    result = http_json(XAI_URL, token=api_key, payload=payload, timeout=600)
    try:
        content = result["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as error:
        die(f"xAI response missing message content: {error}; body={result!r}"[:2000])
    if not isinstance(content, str) or not content.strip():
        die("xAI returned an empty review")
    return content.strip()


def post_review(repo: str, pr_number: str, head_sha: str, body: str, token: str) -> None:
    if len(body) > MAX_REVIEW_CHARS:
        body = body[: MAX_REVIEW_CHARS - 80] + "\n\n[review truncated to GitHub's size limit]\n"
    payload = {"commit_id": head_sha, "body": body, "event": "COMMENT"}
    http_json(
        f"{GITHUB_API}/repos/{repo}/pulls/{pr_number}/reviews",
        token=token,
        payload=payload,
        timeout=60,
    )


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        die(f"missing required environment variable {name}")
    return value


def cmd_review() -> None:
    api_key = os.environ.get("XAI_API_KEY", "").strip()
    if not api_key:
        die(
            "Repository secret XAI_API_KEY is missing. "
            "kaze must add it from https://console.x.ai (do not invent a key)."
        )
    model = os.environ.get("XAI_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    token = require_env("GITHUB_TOKEN")
    repo = require_env("GITHUB_REPOSITORY")
    pr_number = require_env("PR_NUMBER")
    title = os.environ.get("PR_TITLE", "")
    body = os.environ.get("PR_BODY", "")
    base_sha = require_env("BASE_SHA")
    head_sha = require_env("HEAD_SHA")

    prompt = load_prompt()
    changed = changed_name_status(base_sha, head_sha)
    pack = build_pack(
        title=title,
        body=body,
        base_sha=base_sha,
        head_sha=head_sha,
        changed=changed,
    )
    print(
        f"Packed {len(changed)} changed paths, {len(pack)} chars, model={model}",
        file=sys.stderr,
    )
    review = call_xai(prompt, pack, api_key, model)
    post_review(repo, pr_number, head_sha, ADVISORY_BANNER + review, token)
    print("Posted advisory PR review comment (job stays green on findings).")


def cmd_pack_only() -> None:
    title = os.environ.get("PR_TITLE", "(pack-only)")
    body = os.environ.get("PR_BODY", "")
    base_sha = require_env("BASE_SHA")
    head_sha = require_env("HEAD_SHA")
    changed = changed_name_status(base_sha, head_sha)
    pack = build_pack(
        title=title,
        body=body,
        base_sha=base_sha,
        head_sha=head_sha,
        changed=changed,
    )
    print(pack)


def cmd_self_test() -> None:
    """Prove neighbor walking without calling xAI or GitHub."""
    load_prompt()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        main_svc = root / "app/src/main/java/io/freedriver/app/appliances/ApplianceService.java"
        main_res = root / "app/src/main/java/io/freedriver/app/api/AppliancesResource.java"
        main_filter = root / "app/src/main/java/io/freedriver/app/appliances/AppliancesDisabledFilter.java"
        main_mapper = root / "app/src/main/java/io/freedriver/app/appliances/GoneMapper.java"
        props = root / "app/src/main/resources/application.properties"
        test_svc = root / "app/src/test/java/io/freedriver/app/appliances/ApplianceServiceTest.java"
        test_http = root / "app/src/test/java/io/freedriver/app/api/AppliancesResourceTest.java"
        sibling = root / "app/src/main/java/io/freedriver/app/appliances/CommandRateLimiter.java"
        unrelated = root / "docs/unrelated.md"
        for path in (main_svc, main_res, main_filter, main_mapper, props, test_svc, test_http, sibling, unrelated):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"// {path.name}\n")

        old = Path.cwd()
        os.chdir(root)
        try:
            neighbors = collect_neighbors(["app/src/main/java/io/freedriver/app/appliances/ApplianceService.java"])
        finally:
            os.chdir(old)

    needed = {
        "app/src/main/java/io/freedriver/app/api/AppliancesResource.java",
        "app/src/main/java/io/freedriver/app/appliances/AppliancesDisabledFilter.java",
        "app/src/main/java/io/freedriver/app/appliances/GoneMapper.java",
        "app/src/main/resources/application.properties",
        "app/src/test/java/io/freedriver/app/appliances/ApplianceServiceTest.java",
        "app/src/test/java/io/freedriver/app/api/AppliancesResourceTest.java",
        "app/src/main/java/io/freedriver/app/appliances/CommandRateLimiter.java",
    }
    missing = sorted(needed - set(neighbors))
    if missing:
        die(f"self-test missing neighbors: {missing}; got {neighbors}")
    if "docs/unrelated.md" in neighbors:
        die("self-test leaked an unrelated file")
    print("self-test OK:", ", ".join(sorted(needed)))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--pack-only",
        action="store_true",
        help="Print the full-file + neighbor pack and exit (no API calls).",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Check prompt lock + neighbor walking; no network.",
    )
    args = parser.parse_args()
    if args.self_test:
        cmd_self_test()
        return
    if args.pack_only:
        cmd_pack_only()
        return
    cmd_review()


if __name__ == "__main__":
    main()
