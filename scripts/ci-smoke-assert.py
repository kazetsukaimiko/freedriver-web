#!/usr/bin/env python3
"""Assert the compose smoke job through Caddy (#484)."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path

APP = "https://app.freedriver.io"
AUTH = "https://auth.freedriver.io"
PHONE = os.environ.get("FREEDRIVER_SEED_PHONE", "+15555550100")
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "freedriver-web")
AUTONOMY_META = f"{APP}/api/autonomy/oauth-protected-resource"
DISPATCH_META = f"{APP}/api/dispatcher/oauth-protected-resource"
AUTONOMY_CHALLENGE = f'Bearer resource_metadata="{AUTONOMY_META}"'
DISPATCH_CHALLENGE = f'Bearer resource_metadata="{DISPATCH_META}"'
SSL = ssl._create_unverified_context()
RESOLVE = {
    "app.freedriver.io": "127.0.0.1",
    "auth.freedriver.io": "127.0.0.1",
}

_real_getaddrinfo = socket.getaddrinfo


def _resolve_getaddrinfo(host, port, *args, **kwargs):  # type: ignore[no-untyped-def]
    return _real_getaddrinfo(RESOLVE.get(host, host), port, *args, **kwargs)


socket.getaddrinfo = _resolve_getaddrinfo  # type: ignore[assignment]


class FormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.forms: list[dict] = []
        self._cur: dict | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        ad = {k: (v or "") for k, v in attrs}
        if tag == "form":
            self._cur = {"action": ad.get("action", ""), "id": ad.get("id", ""), "fields": {}}
            self.forms.append(self._cur)
        elif tag in {"input", "button"} and self._cur is not None:
            name = ad.get("name")
            if name:
                self._cur["fields"][name] = ad.get("value") or ""


class Client:
    def __init__(self) -> None:
        self.jar = CookieJar()
        https = urllib.request.HTTPSHandler(context=SSL)
        cookies = urllib.request.HTTPCookieProcessor(self.jar)
        self.opener = urllib.request.build_opener(https, cookies)

    def _open(self, req: urllib.request.Request, follow: bool = True):
        opener = self.opener
        if not follow:
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *args, **kwargs):  # type: ignore[no-untyped-def]
                    return None

            opener = urllib.request.build_opener(https_handler(), NoRedirect(), urllib.request.HTTPCookieProcessor(self.jar))
        return opener.open(req, timeout=30)

    def request(
        self,
        url: str,
        method: str = "GET",
        headers: dict[str, str] | None = None,
        data: bytes | None = None,
        follow: bool = True,
    ) -> tuple[int, dict[str, str], bytes, str]:
        req = urllib.request.Request(url, data=data, method=method)
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with self._open(req, follow=follow) as resp:
                body = resp.read()
                hdrs = {k.lower(): v for k, v in resp.headers.items()}
                return resp.status, hdrs, body, resp.geturl()
        except urllib.error.HTTPError as exc:
            body = exc.read()
            hdrs = {k.lower(): v for k, v in exc.headers.items()}
            location = exc.headers.get("Location") or url
            return exc.code, hdrs, body, location


def https_handler() -> urllib.request.HTTPSHandler:
    return urllib.request.HTTPSHandler(context=SSL)


def env_value(name: str) -> str:
    path = Path("/opt/freedriver-secrets/.env")
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1]
    return ""


def secret_file(name: str) -> str:
    path = Path("/opt/freedriver-secrets") / name
    if not path.is_file():
        raise SystemExit(f"missing secret {path}")
    return path.read_text(encoding="utf-8").strip()


def replica_ids(service: str) -> list[str]:
    raw = subprocess.check_output(
        [
            "docker",
            "ps",
            "-q",
            "--filter",
            f"label=com.docker.compose.project={PROJECT}",
            "--filter",
            f"label=com.docker.compose.service={service}",
        ],
        text=True,
    )
    return [line.strip() for line in raw.splitlines() if line.strip()]


def assert_replica_counts() -> None:
    for service in ("dashboard", "agent-dispatcher", "autonomy-mcp"):
        ids = replica_ids(service)
        if len(ids) != 2:
            raise SystemExit(f"{service} replica count {len(ids)}, want 2")
        print("replicas", service, len(ids))


def wait_caddy_dns(name: str, want: int = 2) -> None:
    # Alpine getent hosts uses gethostbyname (one A record). Caddy dynamic a
    # uses LookupIP; ahostsv4 lists every IPv4 address the same way.
    container = f"{PROJECT}-caddy-1"
    last = ""
    for _ in range(30):
        try:
            raw = subprocess.check_output(
                ["docker", "exec", container, "getent", "ahostsv4", name],
                text=True,
                stderr=subprocess.STDOUT,
            )
        except subprocess.CalledProcessError as exc:
            last = (exc.output or "").strip()
            time.sleep(1)
            continue
        ips = {line.split()[0] for line in raw.splitlines() if line.strip()}
        last = " ".join(sorted(ips))
        if len(ips) >= want:
            print("caddy dns", name, last)
            return
        time.sleep(1)
    raise SystemExit(f"caddy dns {name} {last!r}, want {want} addresses")


def cookie_header(jar: CookieJar) -> str:
    return "; ".join(f"{cookie.name}={cookie.value}" for cookie in jar)


def raw_request(
    url: str,
    method: str,
    headers: dict[str, str],
    data: bytes | None = None,
) -> tuple[int, dict[str, str], bytes]:
    req = urllib.request.Request(url, data=data, method=method)
    for key, value in headers.items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, context=SSL, timeout=30) as resp:
            body = resp.read()
            hdrs = {k.lower(): v for k, v in resp.headers.items()}
            return resp.status, hdrs, body
    except urllib.error.HTTPError as exc:
        body = exc.read()
        hdrs = {k.lower(): v for k, v in exc.headers.items()}
        return exc.code, hdrs, body


def authorization_code_token(client: Client, client_id: str) -> str:
    verifier, challenge = pkce()
    redirect = "https://grok.com/connectors-oauth-exchange-code/"
    params = urllib.parse.urlencode(
        {
            "client_id": client_id,
            "response_type": "code",
            "redirect_uri": redirect,
            "scope": "openid",
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        }
    )
    status, headers, body, location = client.request(
        f"{AUTH}/realms/freedriver/protocol/openid-connect/auth?{params}",
        follow=False,
    )
    if status not in {302, 303}:
        raise SystemExit(
            f"{client_id} auth HTTP {status} location={headers.get('location') or location} body={body[:200]!r}"
        )
    target = headers.get("location") or location
    parsed = urllib.parse.urlparse(target)
    code = urllib.parse.parse_qs(parsed.query).get("code", [""])[0]
    if not code:
        raise SystemExit(f"{client_id} auth missing code in {target}")
    token_body = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "client_id": client_id,
            "code": code,
            "redirect_uri": redirect,
            "code_verifier": verifier,
        }
    ).encode()
    status, _, body, _ = client.request(
        f"{AUTH}/realms/freedriver/protocol/openid-connect/token",
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        data=token_body,
    )
    if status != 200:
        raise SystemExit(f"{client_id} token HTTP {status} body={body[:300]!r}")
    return json.loads(body)["access_token"]


def access_token_claims(token: str) -> dict:
    parts = token.split(".")
    if len(parts) < 2:
        return {}
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        data = json.loads(base64.urlsafe_b64decode(payload))
    except (json.JSONDecodeError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def token_role_summary(token: str) -> str:
    claims = access_token_claims(token)
    roles = (claims.get("realm_access") or {}).get("roles")
    return (
        f"azp={claims.get('azp')!r} actor={claims.get('actor')!r} "
        f"device_id={claims.get('device_id')!r} realm_access.roles={roles!r}"
    )


def client_credentials_token(client_id: str, secret: str) -> str:
    token_body = urllib.parse.urlencode(
        {
            "grant_type": "client_credentials",
            "client_id": client_id,
            "client_secret": secret,
        }
    ).encode()
    status, _, body = raw_request(
        f"{AUTH}/realms/freedriver/protocol/openid-connect/token",
        "POST",
        {"Content-Type": "application/x-www-form-urlencoded"},
        token_body,
    )
    if status != 200:
        raise SystemExit(f"{client_id} client-credentials HTTP {status} body={body[:300]!r}")
    return json.loads(body)["access_token"]


def mcp_headers(token: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }


def mcp_post(client: Client, url: str, headers: dict[str, str], payload: dict) -> tuple[int, dict[str, str], dict]:
    status, resp_headers, body, _ = client.request(
        url,
        method="POST",
        headers=headers,
        data=json.dumps(payload).encode(),
    )
    try:
        parsed = json.loads(body) if body else {}
    except json.JSONDecodeError:
        parsed = {}
    return status, resp_headers, parsed if isinstance(parsed, dict) else {}


def mcp_session(client: Client, url: str, token: str) -> dict[str, str]:
    headers = mcp_headers(token)
    status, resp_headers, payload = mcp_post(
        client,
        url,
        headers,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "ci-smoke", "version": "0"},
            },
        },
    )
    if status != 200:
        raise SystemExit(f"{url} initialize HTTP {status} body={payload}")
    session = resp_headers.get("mcp-session-id")
    if not session:
        raise SystemExit(f"{url} initialize missing MCP-Session-Id")
    headers["MCP-Session-Id"] = session
    return headers


def mcp_ten_calls(client: Client, url: str, headers: dict[str, str], start_id: int = 2) -> None:
    for offset in range(10):
        rpc_id = start_id + offset
        status, _, payload = mcp_post(
            client,
            url,
            headers,
            {"jsonrpc": "2.0", "id": rpc_id, "method": "tools/list"},
        )
        if status != 200:
            raise SystemExit(f"{url} tools/list #{rpc_id} HTTP {status} body={payload}")
        if payload.get("error"):
            raise SystemExit(f"{url} tools/list #{rpc_id} error {payload['error']}")
        if "result" not in payload:
            raise SystemExit(f"{url} tools/list #{rpc_id} missing result {payload}")
    print("mcp session", url, "10 consecutive calls")


def parse_forms(html: str) -> list[dict]:
    parser = FormParser()
    parser.feed(html)
    return parser.forms


def find_form(forms: list[dict], *needles: str) -> dict:
    for form in forms:
        blob = form.get("id", "") + " " + " ".join(form.get("fields", {}))
        if any(needle in blob for needle in needles):
            return form
    raise SystemExit(f"missing form {needles} in { [f.get('id') for f in forms] }")


def post_form(client: Client, form: dict, updates: dict[str, str]) -> tuple[int, dict[str, str], bytes, str]:
    fields = dict(form["fields"])
    fields.update(updates)
    action = form["action"]
    if action.startswith("/"):
        action = AUTH + action
    body = urllib.parse.urlencode(fields).encode()
    return client.request(
        action,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        data=body,
    )


def stub_code() -> str:
    container = f"{PROJECT}-twilio-stub-1"
    for _ in range(20):
        raw = subprocess.check_output(
            [
                "docker",
                "exec",
                container,
                "python3",
                "-c",
                "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8080/_code').read().decode())",
            ],
            text=True,
        )
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            time.sleep(1)
            continue
        code = payload.get("code")
        if code:
            return str(code)
        time.sleep(1)
    raise SystemExit("Twilio stub never exposed a code")


def pkce() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(32)
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return verifier, challenge


def assert_build(client: Client) -> None:
    status, _, body, _ = client.request(f"{APP}/api/build")
    if status != 200:
        raise SystemExit(f"/api/build HTTP {status}")
    payload = json.loads(body)
    if not payload.get("build"):
        raise SystemExit("/api/build missing stamp")
    print("stamp", payload["build"])


def assert_mcp_challenges(client: Client) -> None:
    cases = (
        (f"{APP}/autonomy/mcp", AUTONOMY_CHALLENGE, AUTONOMY_META),
        (f"{APP}/mcp", DISPATCH_CHALLENGE, DISPATCH_META),
        (f"{APP}/dispatch/mcp", DISPATCH_CHALLENGE, DISPATCH_META),
    )
    for path, challenge, meta in cases:
        status, headers, _, _ = client.request(path)
        if status != 401:
            raise SystemExit(f"{path} HTTP {status}, want 401")
        got = headers.get("www-authenticate", "")
        if challenge not in got:
            raise SystemExit(f"{path} WWW-Authenticate {got!r}")
        meta_status, _, meta_body, _ = client.request(meta)
        if meta_status != 200:
            raise SystemExit(f"{meta} HTTP {meta_status}")
        document = json.loads(meta_body)
        if "authorization_servers" not in document:
            raise SystemExit(f"{meta} missing authorization_servers")
        print("challenge", path)


def assert_headers_anonymous(client: Client) -> None:
    last = (0, "", b"")
    for _ in range(5):
        status, _, body, url = client.request(
            f"{APP}/api/mcp/appliances",
            headers={
                "X-Freedriver-Subject": "attacker",
                "X-Freedriver-Roles": "portal-admin,mcp-user,dashboard",
                "X-Freedriver-Internal": "not-a-secret",
                "X-Requested-With": "XMLHttpRequest",
                "Accept": "application/json",
            },
            follow=False,
        )
        snippet = body[:200]
        if status in {200, 204}:
            raise SystemExit(
                f"outside hop headers reached the dashboard ({status}) url={url} body={snippet!r}"
            )
        if status in {401, 302, 303, 499}:
            print("headers anonymous", status, flush=True)
            return
        last = (status, url, snippet)
        time.sleep(1)
    raise SystemExit(f"outside headers HTTP {last[0]} url={last[1]} body={last[2]!r}")


def assert_phone_sign_in(client: Client) -> None:
    status, headers, body, url = client.request(f"{APP}/login")
    html = body.decode("utf-8", "replace")
    if "freedriver-phone-username" not in html and "username" not in html:
        raise SystemExit(f"login page missing phone form HTTP {status} url={url}")
    form = find_form(parse_forms(html), "freedriver-phone-username", "username")
    status, _, body, url = post_form(client, form, {"username": PHONE, "continue": "1"})
    html = body.decode("utf-8", "replace")
    if status >= 400:
        raise SystemExit(f"phone submit HTTP {status}")
    form = find_form(parse_forms(html), "freedriver-sms-code", "code")
    code = stub_code()
    status, headers, body, url = post_form(client, form, {"code": code})
    if status >= 400:
        raise SystemExit(f"code submit HTTP {status} body={body[:300]!r}")
    html = body.decode("utf-8", "replace")
    forms = parse_forms(html)
    if any("firstName" in (form.get("fields") or {}) for form in forms):
        form = find_form(forms, "firstName")
        status, headers, body, url = post_form(
            client,
            form,
            {
                "firstName": "Kaze",
                "lastName": "Smoke",
                "email": "kaze@freedriver.invalid",
            },
        )
        if status >= 400:
            raise SystemExit(f"profile submit HTTP {status} body={body[:300]!r}")
    if "app.freedriver.io" not in url:
        raise SystemExit(f"phone sign-in landed on {url} HTTP {status}")
    print("phone sign-in", status, url.split("?", 1)[0])


def assert_login_across_replicas(client: Client) -> None:
    last = (0, b"")
    for i in range(20):
        status, _, body, url = client.request(f"{APP}/api/appliances")
        if status != 200:
            raise SystemExit(
                f"login cookie rejected on replica pass {i} HTTP {status} url={url} body={body[:200]!r}"
            )
        last = (status, body)
    if last[0] != 200:
        raise SystemExit("login cookie never accepted")
    print("login accepted across dashboard replicas")


def assert_mcp_tools(client: Client) -> None:
    autonomy_token = authorization_code_token(client, "freedriver-grok-mcp")
    autonomy = f"{APP}/autonomy/mcp"
    session_headers = mcp_session(client, autonomy, autonomy_token)
    status, _, payload = mcp_post(
        client,
        autonomy,
        session_headers,
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    )
    if status != 200:
        raise SystemExit(f"tools/list HTTP {status}")
    names = [tool.get("name") for tool in payload.get("result", {}).get("tools", [])]
    if "home_status" not in names:
        raise SystemExit(f"tools/list missing home_status: {names}")
    status, _, payload = mcp_post(
        client,
        autonomy,
        session_headers,
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "home_status", "arguments": {}},
        },
    )
    if status != 200:
        raise SystemExit(f"home_status HTTP {status} body={payload}")
    if payload.get("result", {}).get("isError"):
        raise SystemExit(f"home_status error {payload}")
    mcp_ten_calls(client, autonomy, session_headers, start_id=10)

    dispatcher_path = f"{APP}/mcp"
    dispatcher_token = authorization_code_token(client, "freedriver-dispatcher")
    try:
        headers = mcp_session(client, dispatcher_path, dispatcher_token)
    except SystemExit:
        device_token = client_credentials_token(
            "freedriver-openbot",
            secret_file("keycloak-device-freedriver-openbot.secret"),
        )
        headers = mcp_session(client, dispatcher_path, device_token)
    mcp_ten_calls(client, dispatcher_path, headers)
    print("mcp tools ok")


def assert_invite_spent_once(client: Client) -> None:
    status, _, body, _ = client.request(f"{APP}/api/phones")
    if status != 200:
        raise SystemExit(f"GET /api/phones HTTP {status} body={body[:300]!r}")
    listed = json.loads(body)
    token = listed.get("csrfToken")
    if not token:
        raise SystemExit("GET /api/phones missing csrfToken")
    phone = "+15555550999"
    payload = json.dumps({"phone": phone, "name": "Smoke"}).encode()
    headers = {
        "Content-Type": "application/json",
        "X-CSRF-Token": token,
        "Cookie": cookie_header(client.jar),
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "application/json",
    }

    def create() -> int:
        code, _, _ = raw_request(f"{APP}/api/invites", "POST", headers, payload)
        return code

    with ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda _: create(), (0, 1)))
    if sorted(statuses) != [201, 409]:
        raise SystemExit(f"concurrent invite statuses {statuses}, want 201 and 409")
    print("invite spent once", statuses)


def assert_scheduled_switches_once() -> None:
    container = f"{PROJECT}-mongodb-1"
    docs = [
        {
            "_id": f"smoke-switch-{i:02d}",
            "instanceId": "11111111-1111-1111-1111-111111111111",
            "applianceName": f"smoke-{i:02d}",
            "on": True,
            "dueAt": "1970-01-01T00:00:00.000Z",
            "subject": "ci-smoke",
            "attempts": 0,
        }
        for i in range(20)
    ]
    script = (
        "const docs = "
        + json.dumps(docs)
        + ";\n"
        + "docs.forEach(d => { d.dueAt = new Date(d.dueAt); });\n"
        + 'db.getCollection("scheduled-switches").deleteMany({subject: "ci-smoke"});\n'
        + 'db.getCollection("scheduled-switches").insertMany(docs);\n'
        + 'print(db.getCollection("scheduled-switches").countDocuments({subject: "ci-smoke"}));\n'
    )
    out = subprocess.check_output(
        ["docker", "exec", container, "mongosh", "--quiet", "freedriver", "--eval", script],
        text=True,
    )
    inserted = next(
        (line.strip() for line in reversed(out.splitlines()) if line.strip().isdigit()),
        out.strip(),
    )
    if inserted != "20":
        raise SystemExit(f"scheduled-switches insert count {inserted!r}, want 20")
    rows: list[dict] = []
    for _ in range(20):
        raw = subprocess.check_output(
            [
                "docker",
                "exec",
                container,
                "mongosh",
                "--quiet",
                "freedriver",
                "--eval",
                'JSON.stringify(db.getCollection("scheduled-switches").find({subject:"ci-smoke"}).toArray().map(r => ({id: r._id, attempts: r.attempts})))',
            ],
            text=True,
        )
        rows = json.loads(raw)
        if len(rows) == 20 and all(int(row.get("attempts") or 0) >= 1 for row in rows):
            break
        time.sleep(1)
    if len(rows) != 20:
        raise SystemExit(f"scheduled-switches remaining {len(rows)}, want 20")
    attempts = [int(row.get("attempts") or 0) for row in rows]
    if any(value != 1 for value in attempts):
        raise SystemExit(f"scheduled-switches attempts {attempts}, want each 1")
    print("scheduled switches each fired once")


def assert_dispatcher_claim_race(client: Client) -> None:
    device_token = client_credentials_token(
        "freedriver-openbot",
        secret_file("keycloak-device-freedriver-openbot.secret"),
    )
    dispatcher_token = authorization_code_token(client, "freedriver-dispatcher")
    report = json.dumps(
        {
            "hostname": "smoke-host.local",
            "bots": [{"name": "yuni", "state": "idle", "activity": "waiting"}],
        }
    ).encode()
    status, _, body = raw_request(
        f"{APP}/api/dispatcher/host/report",
        "POST",
        {
            "Authorization": f"Bearer {device_token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        report,
    )
    if status != 200:
        raise SystemExit(
            f"host report HTTP {status} body={body[:300]!r} {token_role_summary(device_token)}"
        )
    submit = json.dumps(
        {
            "deviceIds": ["freedriver-openbot"],
            "agentName": "yuni",
            "prompt": "smoke-claim",
            "channel": "TEXT",
        }
    ).encode()
    status, _, body = raw_request(
        f"{APP}/api/dispatcher/jobs",
        "POST",
        {
            "Authorization": f"Bearer {dispatcher_token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        submit,
    )
    if status != 200:
        raise SystemExit(f"submit job HTTP {status} body={body[:300]!r}")
    job = json.loads(body)
    queued = [row.get("assignmentId") for row in job.get("assignments", [])]
    if len(queued) != 1 or not queued[0]:
        raise SystemExit(f"submit job assignments {job}")
    take_headers = {
        "Authorization": f"Bearer {device_token}",
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    take_body = json.dumps({"waitSeconds": 0}).encode()

    def take() -> tuple[int, list]:
        code, _, raw = raw_request(
            f"{APP}/api/dispatcher/host/take",
            "POST",
            take_headers,
            take_body,
        )
        try:
            parsed = json.loads(raw) if raw else []
        except json.JSONDecodeError:
            parsed = []
        return code, parsed if isinstance(parsed, list) else []

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: take(), (0, 1)))
    claimed: list[str] = []
    empties = 0
    for code, rows in results:
        if code != 200:
            raise SystemExit(f"host take HTTP {code} body={rows}")
        if not rows:
            empties += 1
            continue
        for row in rows:
            if row.get("state") == "CLAIMED" and row.get("assignmentId"):
                claimed.append(row["assignmentId"])
    if empties != 1 or len(claimed) != 1 or claimed[0] != queued[0]:
        raise SystemExit(
            f"claim race empties={empties} claimed={claimed} queued={queued} results={results}"
        )
    print("dispatcher claim race one winner", claimed[0])


def main() -> int:
    client = Client()
    assert_build(client)
    assert_mcp_challenges(client)
    assert_headers_anonymous(client)
    assert_replica_counts()
    for name in ("dashboard", "agent-dispatcher", "autonomy-mcp"):
        wait_caddy_dns(name)
    assert_phone_sign_in(client)
    assert_login_across_replicas(client)
    assert_mcp_tools(client)
    assert_invite_spent_once(client)
    assert_scheduled_switches_once()
    assert_dispatcher_claim_race(client)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
