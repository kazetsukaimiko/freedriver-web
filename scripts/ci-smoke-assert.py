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
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path

APP = "https://app.freedriver.io"
AUTH = "https://auth.freedriver.io"
PHONE = os.environ.get("FREEDRIVER_SEED_PHONE", "+15555550100")
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "freedriver-web")
ROOT = Path(__file__).resolve().parent.parent
SECRETS = Path(os.environ.get("FREEDRIVER_SMOKE_SECRETS", "/opt/freedriver-secrets"))
ENV_FILE = Path(os.environ.get("FREEDRIVER_SMOKE_ENV", str(SECRETS / ".env")))
FIXTURE_LABEL = "Fixture"
FIXTURE_APPLIANCES = ("hallway", "fridge")
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
    if not ENV_FILE.is_file():
        return ""
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1]
    return ""


def set_env_value(name: str, value: str) -> None:
    lines: list[str] = []
    replaced = False
    if ENV_FILE.is_file():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                lines.append(f"{name}={value}")
                replaced = True
            else:
                lines.append(line)
    if not replaced:
        lines.append(f"{name}={value}")
    ENV_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def compose(*args: str) -> None:
    subprocess.check_call(
        [
            "docker",
            "compose",
            "--env-file",
            str(ENV_FILE),
            "-f",
            str(ROOT / "docker-compose.yml"),
            "-f",
            str(ROOT / "docker-compose.ci.yml"),
            *args,
        ]
    )


def as_root(argv: list[str]) -> None:
    if os.geteuid() == 0:
        subprocess.check_call(argv)
    else:
        subprocess.check_call(["sudo", *argv])


def mcp_text(body: bytes) -> str:
    payload = json.loads(body)
    if payload.get("result", {}).get("isError"):
        raise SystemExit(f"mcp error {body[:300]!r}")
    contents = payload.get("result", {}).get("content") or []
    texts = [item.get("text", "") for item in contents if isinstance(item, dict)]
    return "\n".join(texts)


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


def provision_fixture_house(client: Client) -> tuple[str, str]:
    status, _, body, _ = client.request(f"{APP}/api/instances")
    if status != 200:
        raise SystemExit(f"GET /api/instances HTTP {status} body={body[:300]!r}")
    listed = json.loads(body)
    csrf = listed.get("csrfToken") or ""
    if not csrf:
        raise SystemExit("GET /api/instances missing csrfToken")
    status, _, body, _ = client.request(
        f"{APP}/api/instances",
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-CSRF-Token": csrf,
        },
        data=json.dumps({"label": FIXTURE_LABEL}).encode(),
    )
    if status not in {200, 201}:
        raise SystemExit(f"POST /api/instances HTTP {status} body={body[:300]!r}")
    created = json.loads(body)
    instance_id = created.get("instanceId") or ""
    password = created.get("password") or ""
    if not instance_id or not password:
        raise SystemExit("POST /api/instances missing instanceId or password")
    print("fixture house", instance_id)
    return instance_id, password


def apply_fixture_acl(instance_id: str) -> None:
    fd, tmp_name = tempfile.mkstemp(prefix="smoke-acl-", suffix=".acl")
    os.close(fd)
    tmp = Path(tmp_name)
    dest = SECRETS / "mosquitto" / "acl"
    try:
        subprocess.check_call(
            [
                str(ROOT / "scripts/provision-mosquitto.sh"),
                "--acl-only",
                "--instance-id",
                instance_id,
                "--acl-out",
                str(tmp),
            ]
        )
        as_root(["install", "-m", "0644", str(tmp), str(dest)])
    finally:
        tmp.unlink(missing_ok=True)
    compose("kill", "-s", "HUP", "mosquitto")


def start_fixture_house(instance_id: str, password: str) -> None:
    set_env_value("FIXTURE_INSTANCE_ID", instance_id)
    set_env_value("FIXTURE_HOUSE_PASSWORD", password)
    compose("up", "-d", "--no-build", "fixture-house")
    container = f"{PROJECT}-fixture-house-1"
    for _ in range(30):
        try:
            raw = subprocess.check_output(
                [
                    "docker",
                    "exec",
                    container,
                    "python3",
                    "-c",
                    "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8080/health').getcode())",
                ],
                text=True,
                stderr=subprocess.STDOUT,
            )
        except subprocess.CalledProcessError:
            time.sleep(2)
            continue
        if raw.strip() == "200":
            print("fixture-house ready")
            return
        time.sleep(2)
    raise SystemExit("fixture-house never became ready")


def fixture_command_count() -> int:
    container = f"{PROJECT}-fixture-house-1"
    raw = subprocess.check_output(
        [
            "docker",
            "exec",
            container,
            "python3",
            "-c",
            "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8080/commands').read().decode())",
        ],
        text=True,
    )
    payload = json.loads(raw)
    return int(payload.get("count", -1))


def assert_mcp_tools(client: Client) -> None:
    verifier, challenge = pkce()
    redirect = "https://grok.com/connectors-oauth-exchange-code/"
    params = urllib.parse.urlencode(
        {
            "client_id": "freedriver-grok-mcp",
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
            f"mcp auth HTTP {status} location={headers.get('location') or location} body={body[:200]!r}"
        )
    target = headers.get("location") or location
    parsed = urllib.parse.urlparse(target)
    code = urllib.parse.parse_qs(parsed.query).get("code", [""])[0]
    if not code:
        raise SystemExit(f"mcp auth missing code in {target}")
    token_body = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "client_id": "freedriver-grok-mcp",
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
        raise SystemExit(f"mcp token HTTP {status} body={body[:300]!r}")
    token = json.loads(body)["access_token"]
    session_headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    init = json.dumps(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "ci-smoke", "version": "0"},
            },
        }
    ).encode()
    status, headers, body, _ = client.request(
        f"{APP}/autonomy/mcp",
        method="POST",
        headers=session_headers,
        data=init,
    )
    if status != 200:
        raise SystemExit(f"mcp initialize HTTP {status} body={body[:300]!r}")
    session = headers.get("mcp-session-id")
    if not session:
        raise SystemExit("mcp initialize missing MCP-Session-Id")
    session_headers["MCP-Session-Id"] = session
    listed = json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}).encode()
    status, _, body, _ = client.request(
        f"{APP}/autonomy/mcp",
        method="POST",
        headers=session_headers,
        data=listed,
    )
    if status != 200:
        raise SystemExit(f"tools/list HTTP {status}")
    payload = json.loads(body)
    names = [tool.get("name") for tool in payload.get("result", {}).get("tools", [])]
    for required in ("home_status", "list_appliances", "switch_appliance"):
        if required not in names:
            raise SystemExit(f"tools/list missing {required}: {names}")

    def call_tool(rpc_id: int, name: str, arguments: dict) -> tuple[int, bytes]:
        payload = json.dumps(
            {
                "jsonrpc": "2.0",
                "id": rpc_id,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            }
        ).encode()
        status, _, body, _ = client.request(
            f"{APP}/autonomy/mcp",
            method="POST",
            headers=session_headers,
            data=payload,
        )
        return status, body

    houses: list[dict] = []
    for _ in range(20):
        status, body = call_tool(3, "list_appliances", {})
        if status != 200:
            raise SystemExit(f"list_appliances HTTP {status} body={body[:300]!r}")
        try:
            houses = json.loads(mcp_text(body))
        except json.JSONDecodeError:
            houses = []
        if houses and houses[0].get("appliances"):
            break
        time.sleep(2)
    if not houses:
        raise SystemExit("list_appliances returned no houses")
    house = houses[0]
    instance_id = house.get("instanceId") or ""
    if instance_id != os.environ.get("FIXTURE_INSTANCE_ID", instance_id):
        raise SystemExit(f"list_appliances instance {instance_id}")
    got = {item.get("name"): item.get("state") for item in house.get("appliances") or []}
    for name in FIXTURE_APPLIANCES:
        if name not in got:
            raise SystemExit(f"list_appliances missing {name}: {got}")
    print("appliances", sorted(got))

    before = fixture_command_count()
    status, body = call_tool(
        4,
        "switch_appliance",
        {"instanceId": instance_id, "applianceName": "hallway", "on": False},
    )
    if status != 200:
        raise SystemExit(f"switch_appliance HTTP {status} body={body[:300]!r}")
    spoken = mcp_text(body)
    if "turned off" not in spoken.lower():
        raise SystemExit(f"switch_appliance not confirmed: {spoken!r}")
    after = fixture_command_count()
    if after - before != 1:
        raise SystemExit(f"fixture-house saw {after - before} commands, want 1")
    print("switch_appliance confirmed")

    status, body = call_tool(5, "home_status", {})
    if status != 200:
        raise SystemExit(f"home_status HTTP {status} body={body[:300]!r}")
    spoken = mcp_text(body)
    if "broker is connected" not in spoken.lower():
        raise SystemExit(f"home_status {spoken!r}")
    print("mcp tools ok")


def main() -> int:
    client = Client()
    assert_build(client)
    assert_mcp_challenges(client)
    assert_headers_anonymous(client)
    assert_phone_sign_in(client)
    instance_id, password = provision_fixture_house(client)
    apply_fixture_acl(instance_id)
    start_fixture_house(instance_id, password)
    os.environ["FIXTURE_INSTANCE_ID"] = instance_id
    assert_mcp_tools(client)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
