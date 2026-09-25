#!/usr/bin/env python3
"""SMS OTP stub for the Keycloak SPI. HTTP contract and limits: docs/sms-otp.md.

OtpService holds the rules: a registry of provisioned numbers (phone -> Keycloak
username), 6-digit codes that expire, and per-phone limits on sends and wrong codes.
main() starts it with an empty registry and no SMS sender, so /health, send and
verify answer 503 until kaze's Quarkus service (#107) replaces this image.
"""

from __future__ import annotations

import hmac
import json
import os
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

PLACEHOLDER = "placeholder-not-a-live-secret"
HEADER = "X-Freedriver-Sms-Secret"
MAX_BODY = 4096

CODE_TTL_SECONDS = 300
WINDOW_SECONDS = 900
MAX_SENDS_PER_WINDOW = 5
MAX_WRONG_CODES_PER_WINDOW = 5

PHONE_RE = re.compile(r"^\+[1-9][0-9]{7,14}$")
CODE_RE = re.compile(r"^[0-9]{6}$")

SENT = {"status": "sent"}
INVALID_CODE = {"error": "invalid-code"}
BAD_REQUEST = {"error": "bad-request"}
RATE_LIMITED = {"error": "rate-limited"}
STUB = {"error": "stub", "otp": "unavailable", "issue": "107"}

Sender = Callable[[str, str], None]


def usable_secret(raw: str | None) -> str | None:
    if raw is None:
        return None
    secret = raw.strip()
    if not secret or secret == PLACEHOLDER:
        return None
    return secret


def authorize(secret: str | None, header: str | None) -> bool:
    """True when the shared secret is set and the header matches it."""
    if secret is None or header is None:
        return False
    return hmac.compare_digest(header.encode("utf-8"), secret.encode("utf-8"))


class OtpService:
    """Code issue and check for provisioned numbers, with per-phone limits.

    Every well-formed phone gets the same send response, and limits are counted
    for every phone, so responses are the same for provisioned and unknown numbers.
    """

    def __init__(
        self,
        numbers: dict[str, str] | None = None,
        sender: Sender | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.numbers = dict(numbers or {})
        self.sender = sender
        self.clock = clock
        self.lock = threading.Lock()
        self.pending: dict[str, tuple[str, float]] = {}
        self.sends: dict[str, list[float]] = {}
        self.wrong: dict[str, list[float]] = {}

    def ready(self) -> bool:
        return self.sender is not None

    def _recent(self, table: dict[str, list[float]], phone: str, now: float) -> list[float]:
        kept = [t for t in table.get(phone, []) if now - t < WINDOW_SECONDS]
        if kept:
            table[phone] = kept
        else:
            table.pop(phone, None)
        return kept

    def send(self, phone: object) -> tuple[int, dict]:
        if not isinstance(phone, str) or not PHONE_RE.match(phone):
            return 400, BAD_REQUEST
        if self.sender is None:
            return 503, STUB
        with self.lock:
            now = self.clock()
            recent = self._recent(self.sends, phone, now)
            if len(recent) >= MAX_SENDS_PER_WINDOW:
                return 429, RATE_LIMITED
            self.sends[phone] = recent + [now]
            if phone not in self.numbers:
                return 200, SENT
            code = f"{secrets.randbelow(1_000_000):06d}"
            self.pending[phone] = (code, now + CODE_TTL_SECONDS)
        try:
            self.sender(phone, code)
        except Exception:  # noqa: BLE001 - the response stays "sent" for every number
            print("sms: delivery failed", file=sys.stderr)
        return 200, SENT

    def verify(self, phone: object, code: object) -> tuple[int, dict]:
        if not isinstance(phone, str) or not PHONE_RE.match(phone) or not isinstance(code, str):
            return 400, BAD_REQUEST
        if self.sender is None:
            return 503, STUB
        with self.lock:
            now = self.clock()
            wrong = self._recent(self.wrong, phone, now)
            entry = self.pending.get(phone)
            if entry is not None and entry[1] <= now:
                self.pending.pop(phone, None)
                entry = None
            if (
                len(wrong) < MAX_WRONG_CODES_PER_WINDOW
                and entry is not None
                and CODE_RE.match(code)
                and hmac.compare_digest(entry[0], code)
                and phone in self.numbers
            ):
                self.pending.pop(phone, None)
                self.wrong.pop(phone, None)
                return 200, {"username": self.numbers[phone]}
            wrong.append(now)
            self.wrong[phone] = wrong
            if len(wrong) >= MAX_WRONG_CODES_PER_WINDOW:
                self.pending.pop(phone, None)
            return 400, INVALID_CODE


class StubHandler(BaseHTTPRequestHandler):
    secret: str | None = None
    service: OtpService = OtpService()
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        # Client address and request line.
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _send(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.split("?", 1)[0] == "/health":
            if self.service.ready():
                self._send(200, {"status": "ok"})
            else:
                self._send(503, {"status": "stub", "otp": "unavailable", "issue": "107"})
            return
        self._send(404, {"error": "not-found"})

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path not in ("/otp/send", "/otp/verify"):
            self._send(404, {"error": "not-found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            self._send(400, BAD_REQUEST)
            return
        if length < 0 or length > MAX_BODY:
            self._send(413, {"error": "too-large"})
            return
        raw = self.rfile.read(length) if length else b""
        if not authorize(self.secret, self.headers.get(HEADER)):
            self._send(401, {"error": "unauthorized"})
            return
        try:
            body = json.loads(raw or b"{}")
        except (ValueError, UnicodeDecodeError):
            body = None
        if not isinstance(body, dict):
            self._send(400, BAD_REQUEST)
            return
        if path == "/otp/send":
            status, payload = self.service.send(body.get("phone"))
        else:
            status, payload = self.service.verify(body.get("phone"), body.get("code"))
        self._send(status, payload)

    def do_PUT(self) -> None:  # noqa: N802
        self._send(405, {"error": "method-not-allowed"})

    def do_DELETE(self) -> None:  # noqa: N802
        self._send(405, {"error": "method-not-allowed"})


def main() -> None:
    secret = usable_secret(os.environ.get("SMS_OTP_SHARED_SECRET"))
    host = os.environ.get("SMS_STUB_BIND", "0.0.0.0")
    port = int(os.environ.get("SMS_STUB_PORT", "8080"))
    StubHandler.secret = secret
    StubHandler.service = OtpService()
    if secret is None:
        print(
            "sms stub: SMS_OTP_SHARED_SECRET is empty or the placeholder; send/verify return 401",
            file=sys.stderr,
        )
    else:
        print(
            "sms stub: shared secret set; send/verify return 503 until #107",
            file=sys.stderr,
        )
    server = ThreadingHTTPServer((host, port), StubHandler)
    print(f"sms stub listening on {host}:{port}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
