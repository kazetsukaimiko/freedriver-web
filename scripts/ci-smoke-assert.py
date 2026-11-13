#!/usr/bin/env python3
"""Assert the compose smoke job through Caddy (#484)."""
from __future__ import annotations

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
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from pathlib import Path

APP = "https://app.freedriver.io"
AUTH = "https://auth.freedriver.io"
PHONE = os.environ.get("FREEDRIVER_SEED_PHONE", "+15555550100")
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "freedriver-web")
INSTANCE = "550e8400-e29b-41d4-a716-446655440000"
HALLWAY = "Hallway (test)"
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
    challenge = urllib.parse.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
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


def assert_subject_stripped(client: Client) -> None:
    secret = env_value("FREEDRIVER_MCP_INTERNAL_SECRET")
    status, _, body, _ = client.request(
        f"{APP}/api/mcp/appliances",
        headers={
            "X-Freedriver-Subject": "attacker",
            "X-Freedriver-Roles": "portal-admin,mcp-user,dashboard",
            "X-Freedriver-Internal": secret,
        },
    )
    if status in {200, 204}:
        raise SystemExit(f"outside X-Freedriver-Subject reached the dashboard ({status})")
    if status not in {401, 302}:
        raise SystemExit(f"outside subject HTTP {status} body={body[:200]!r}")
    print("subject stripped", status)


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
    # Signed-in dashboard or a redirect onto app.freedriver.io.
    if "app.freedriver.io" not in url and status not in {200, 302, 303}:
        raise SystemExit(f"phone sign-in landed on {url} HTTP {status}")
    print("phone sign-in", status, url.split("?", 1)[0])


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
    status, headers, _, location = client.request(
        f"{AUTH}/realms/freedriver/protocol/openid-connect/auth?{params}",
        follow=False,
    )
    if status not in {302, 303}:
        raise SystemExit(f"mcp auth HTTP {status}")
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
        "Accept": "application/json",
    }
    init = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}).encode()
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
    if "switch_appliance" not in names:
        raise SystemExit(f"tools/list missing switch_appliance: {names}")
    call = json.dumps(
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {
                "name": "switch_appliance",
                "arguments": {
                    "instanceId": INSTANCE,
                    "applianceName": HALLWAY,
                    "on": False,
                },
            },
        }
    ).encode()
    status, _, body, _ = client.request(
        f"{APP}/autonomy/mcp",
        method="POST",
        headers=session_headers,
        data=call,
    )
    if status != 200:
        raise SystemExit(f"switch_appliance HTTP {status} body={body[:300]!r}")
    result = json.loads(body)
    if result.get("result", {}).get("isError"):
        raise SystemExit(f"switch_appliance error {body[:300]!r}")
    print("mcp tools ok")


def main() -> int:
    client = Client()
    assert_build(client)
    assert_mcp_challenges(client)
    assert_subject_stripped(client)
    assert_phone_sign_in(client)
    assert_mcp_tools(client)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
