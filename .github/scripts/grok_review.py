#!/usr/bin/env python3
"""Advisory xAI pull-request reviewer for freedriver-web.

Runs from a checkout of the PR head with enough history to diff the base.
The workflow executes main's copy of this file. GROK_REVIEW_PROMPT or --prompt
is the prompt path. GROK_REVIEW_MODE or --mode is engineering or security.
Reads stay inside the working directory. The review pack is the full changed
files plus one-level-out neighbors. Both modes post a COMMENT review.
Engineering exits 0 after posting. Security exits 1 when the verdict line is
request-changes or a finding line is Severity: block. An HTTP 429 or an empty
choices body is tried three times, then the process posts the did-not-run
banner and exits 0.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

PROMPT_PATH = Path(os.environ.get("GROK_REVIEW_PROMPT") or ".github/grok-review-prompt.md")
XAI_URL = "https://api.x.ai/v1/chat/completions"
GITHUB_API = "https://api.github.com"
DEFAULT_MODEL = "grok-4"
ENGINEERING_MODE = "engineering"
SECURITY_MODE = "security"
REVIEW_MODES = {ENGINEERING_MODE, SECURITY_MODE}
# First call plus two retries when xAI returns HTTP 429 or an empty choices body.
MODEL_ATTEMPTS = 3
ENGINEERING_LOCK = "You are a principled engineer reviewing a GitHub pull request on freedriver-web."
SECURITY_LOCK = "You are a security reviewer for a GitHub pull request on freedriver-web."
SECURITY_FAIL_MARK = "fails the Security Review job"
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
BANNERS = {
    ENGINEERING_MODE: (
        "> Principled Engineering Review. kaze's approval is the required human review; this comment is informational.\n\n"
    ),
    SECURITY_MODE: (
        "> Security Review. kaze's approval is the required human review.\n\n"
    ),
}
DID_NOT_RUN = {
    ENGINEERING_MODE: "> Principled Engineering Review did not run.\n",
    SECURITY_MODE: "> Security Review did not run.\n",
}


class ModelRetry(Exception):
    """xAI returned HTTP 429. The caller retries, then posts the did-not-run banner."""


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


def inside_cwd(path: Path) -> bool:
    return not path.is_symlink() and path.resolve().is_relative_to(Path.cwd().resolve())


def read_text_file(path: Path) -> str | None:
    if not path.is_file() or skipped(path) or not inside_cwd(path):
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
    app_touched = any(repo_rel(path).startswith("java/dashboard/") for path in changed)
    if app_touched:
        for root in (Path("java/dashboard/src/main/resources"), Path("java/dashboard/src/test/resources")):
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
        "The diff is the starting point. Review from the FULL files and neighbors.\n\n",
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


def review_mode(explicit: str | None) -> str:
    raw = (explicit or os.environ.get("GROK_REVIEW_MODE") or ENGINEERING_MODE).strip().lower()
    if raw not in REVIEW_MODES:
        die(f"unknown review mode {raw}")
    return raw


def load_prompt(mode: str) -> str:
    if not PROMPT_PATH.is_file():
        die(f"missing {PROMPT_PATH}")
    text = PROMPT_PATH.read_text()
    if mode == ENGINEERING_MODE:
        if ENGINEERING_LOCK not in text:
            die(f"{PROMPT_PATH} is not the #64 lock (principled-engineer prompt missing)")
        if "Comment on the diff only" in text:
            die(f"{PROMPT_PATH} looks like the old paraphrase, not the #64 lock")
        return text
    if mode == SECURITY_MODE:
        if SECURITY_LOCK not in text:
            die(f"{PROMPT_PATH} is not the security review prompt")
        if SECURITY_FAIL_MARK not in text:
            die(f"{PROMPT_PATH} does not say a block finding or request-changes {SECURITY_FAIL_MARK}")
        return text
    die(f"unknown review mode {mode}")


def normalize_review_line(line: str) -> str:
    text = line.strip()
    text = re.sub(r"^[-*]\s+", "", text)
    text = text.replace("**", "").replace("`", "").strip()
    return text


def security_job_fails(review: str) -> bool:
    """True when a finding line is exactly Severity: block or the verdict line is request-changes."""
    for line in review.splitlines():
        text = normalize_review_line(line)
        if re.fullmatch(r"severity:\s*block", text, flags=re.IGNORECASE):
            return True
        if re.fullmatch(r"verdict:\s*request-changes", text, flags=re.IGNORECASE):
            return True
    return False


def completion_text(result: object) -> str | None:
    if not isinstance(result, dict):
        return None
    choices = result.get("choices")
    if not isinstance(choices, list) or not choices:
        return None
    first = choices[0]
    if not isinstance(first, dict):
        return None
    message = first.get("message")
    if not isinstance(message, dict):
        return None
    content = message.get("content")
    if not isinstance(content, str) or not content.strip():
        return None
    return content.strip()


def with_model_retries(fetch) -> str | None:
    for attempt in range(1, MODEL_ATTEMPTS + 1):
        try:
            result = fetch()
        except ModelRetry as error:
            print(f"{error} (attempt {attempt}/{MODEL_ATTEMPTS})", file=sys.stderr)
            continue
        content = completion_text(result)
        if content is None:
            print(f"xAI empty choices (attempt {attempt}/{MODEL_ATTEMPTS})", file=sys.stderr)
            continue
        return content
    return None


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
            raw = response.read().decode("utf-8")
        if url == XAI_URL and not raw.strip():
            return {}
        return json.loads(raw)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        if error.code == 429 and url == XAI_URL:
            raise ModelRetry(f"HTTP 429 from {url}") from error
        die(f"HTTP {error.code} from {url}: {detail}")
    except urllib.error.URLError as error:
        die(f"request failed for {url}: {error}")
    raise AssertionError("unreachable")


def call_xai(system_prompt: str, user_pack: str, api_key: str, model: str) -> str | None:
    payload = {
        "model": model,
        "temperature": 0.2,
        "max_tokens": 8192,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_pack},
        ],
    }

    def fetch() -> dict:
        return http_json(XAI_URL, token=api_key, payload=payload, timeout=600)

    return with_model_retries(fetch)


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


def cmd_review(mode: str) -> None:
    api_key = os.environ.get("XAI_API_KEY", "").strip()
    if not api_key:
        die(
            "Repository secret XAI_API_KEY is missing. "
            "kaze adds it from https://console.x.ai."
        )
    model = os.environ.get("XAI_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    token = require_env("GITHUB_TOKEN")
    repo = require_env("GITHUB_REPOSITORY")
    pr_number = require_env("PR_NUMBER")
    title = os.environ.get("PR_TITLE", "")
    body = os.environ.get("PR_BODY", "")
    base_sha = require_env("BASE_SHA")
    head_sha = require_env("HEAD_SHA")

    prompt = load_prompt(mode)
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
    if review is None:
        post_review(repo, pr_number, head_sha, DID_NOT_RUN[mode], token)
        print("Posted that the review did not run. Job stays green.")
        return
    post_review(repo, pr_number, head_sha, BANNERS[mode] + review, token)
    if mode == SECURITY_MODE and security_job_fails(review):
        die("Security Review found Severity: block or verdict request-changes.")
    if mode == ENGINEERING_MODE:
        print("Posted Principled Engineering Review comment (job stays green on findings).")
        return
    print("Posted Security Review comment.")


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


def load_prompt_at(path: Path, mode: str) -> str:
    global PROMPT_PATH
    previous = PROMPT_PATH
    PROMPT_PATH = path
    try:
        return load_prompt(mode)
    finally:
        PROMPT_PATH = previous


def expect_prompt_rejected(path: Path, mode: str) -> None:
    try:
        load_prompt_at(path, mode)
    except SystemExit:
        return
    die(f"{path} was accepted for mode {mode}")


def cmd_self_test() -> None:
    """Checks neighbor walking, prompt locks, and the security verdict parser on local files."""
    load_prompt(ENGINEERING_MODE)
    security_prompt = Path(".github/grok-security-prompt.md")
    engineering_prompt = Path(".github/grok-review-prompt.md")
    load_prompt_at(security_prompt, SECURITY_MODE)
    expect_prompt_rejected(security_prompt, ENGINEERING_MODE)
    expect_prompt_rejected(engineering_prompt, SECURITY_MODE)
    if not security_job_fails("Verdict: request-changes"):
        die("parser missed verdict request-changes")
    if not security_job_fails("- Severity: block"):
        die("parser missed Severity: block")
    if not security_job_fails("**Verdict:** `request-changes`"):
        die("parser missed a marked verdict line")
    if security_job_fails("Severity: block | should-fix | adjacent | nit"):
        die("parser treated the severity menu as a block finding")
    if security_job_fails("Verdict: approve-as-is"):
        die("parser failed a clean verdict")
    if security_job_fails("Severity: should-fix"):
        die("parser treated should-fix as block")
    if security_job_fails("A severity line Severity: block fails the Security Review job."):
        die("parser treated a sentence as a finding line")
    if completion_text({}) is not None or completion_text({"choices": []}) is not None:
        die("empty choices should be empty")
    if completion_text({"choices": [{"message": {"content": "  "}}]}) is not None:
        die("blank content should be empty")
    if completion_text({"choices": [{"message": {"content": " hi "}}]}) != "hi":
        die("completion text mismatch")
    calls = {"n": 0}

    def rate_limited_then_text() -> dict:
        calls["n"] += 1
        if calls["n"] < MODEL_ATTEMPTS:
            raise ModelRetry("HTTP 429")
        return {"choices": [{"message": {"content": "Verdict: approve-as-is"}}]}

    if with_model_retries(rate_limited_then_text) != "Verdict: approve-as-is":
        die("retries did not return the later review")
    if calls["n"] != MODEL_ATTEMPTS:
        die(f"expected {MODEL_ATTEMPTS} attempts, got {calls['n']}")
    empty_calls = {"n": 0}

    def always_empty() -> dict:
        empty_calls["n"] += 1
        return {"choices": []}

    if with_model_retries(always_empty) is not None or empty_calls["n"] != MODEL_ATTEMPTS:
        die("empty choices should retry twice and then give up")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        main_svc = root / "java/dashboard/src/main/java/io/freedriver/app/appliances/ApplianceService.java"
        main_res = root / "java/dashboard/src/main/java/io/freedriver/app/api/AppliancesResource.java"
        main_filter = root / "java/dashboard/src/main/java/io/freedriver/app/appliances/AppliancesDisabledFilter.java"
        main_mapper = root / "java/dashboard/src/main/java/io/freedriver/app/appliances/GoneMapper.java"
        props = root / "java/dashboard/src/main/resources/application.properties"
        test_svc = root / "java/dashboard/src/test/java/io/freedriver/app/appliances/ApplianceServiceTest.java"
        test_http = root / "java/dashboard/src/test/java/io/freedriver/app/api/AppliancesResourceTest.java"
        sibling = root / "java/dashboard/src/main/java/io/freedriver/app/appliances/CommandRateLimiter.java"
        unrelated = root / "docs/unrelated.md"
        for path in (main_svc, main_res, main_filter, main_mapper, props, test_svc, test_http, sibling, unrelated):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"// {path.name}\n")

        old = Path.cwd()
        os.chdir(root)
        try:
            neighbors = collect_neighbors(["java/dashboard/src/main/java/io/freedriver/app/appliances/ApplianceService.java"])
        finally:
            os.chdir(old)

    needed = {
        "java/dashboard/src/main/java/io/freedriver/app/api/AppliancesResource.java",
        "java/dashboard/src/main/java/io/freedriver/app/appliances/AppliancesDisabledFilter.java",
        "java/dashboard/src/main/java/io/freedriver/app/appliances/GoneMapper.java",
        "java/dashboard/src/main/resources/application.properties",
        "java/dashboard/src/test/java/io/freedriver/app/appliances/ApplianceServiceTest.java",
        "java/dashboard/src/test/java/io/freedriver/app/api/AppliancesResourceTest.java",
        "java/dashboard/src/main/java/io/freedriver/app/appliances/CommandRateLimiter.java",
    }
    missing = sorted(needed - set(neighbors))
    if missing:
        die(f"self-test missing neighbors: {missing}; got {neighbors}")
    if "docs/unrelated.md" in neighbors:
        die("self-test leaked an unrelated file")
    print("self-test OK:", ", ".join(sorted(needed)))


def main() -> None:
    global PROMPT_PATH
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--prompt",
        help="System prompt path. Overrides GROK_REVIEW_PROMPT.",
    )
    parser.add_argument(
        "--mode",
        help="engineering exits 0 on findings. security exits 1 on request-changes or Severity: block. "
        "Default: GROK_REVIEW_MODE or engineering.",
    )
    parser.add_argument(
        "--pack-only",
        action="store_true",
        help="Print the full-file and neighbor pack, then exit before any API call.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Check the prompt lock and neighbor walking on local files.",
    )
    args = parser.parse_args()
    if args.prompt:
        PROMPT_PATH = Path(args.prompt)
    mode = review_mode(args.mode)
    if args.self_test:
        cmd_self_test()
        return
    if args.pack_only:
        cmd_pack_only()
        return
    cmd_review(mode)


if __name__ == "__main__":
    main()
