#!/usr/bin/env python3
"""Fail-closed SMS OTP stub for the Keycloak SPI.

POST /otp/send and POST /otp/verify return 401 unless SMS_OTP_SHARED_SECRET
is set and the X-Freedriver-Sms-Secret header matches it, and 503 when it
matches. GET /health returns 503. Logs carry the client address and the
request line. kaze's Quarkus service (#107) replaces this image.
"""

from __future__ import annotations

import hmac
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PLACEHOLDER = "placeholder-not-a-live-secret"
HEADER = "X-Freedriver-Sms-Secret"
MAX_BODY = 4096


def usable_secret(raw: str | None) -> str | None:
    if raw is None:
        return None
    secret = raw.strip()
    if not secret or secret == PLACEHOLDER:
        return None
    return secret


def authorize(secret: str | None, header: str | None) -> int:
    """401 when the shared secret is missing or wrong; 503 when it matches."""
    if secret is None or header is None:
        return 401
    if not hmac.compare_digest(header.encode("utf-8"), secret.encode("utf-8")):
        return 401
    return 503


class StubHandler(BaseHTTPRequestHandler):
    secret: str | None = None
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
            self._send(
                503,
                {
                    "status": "stub",
                    "otp": "unavailable",
                    "issue": "107",
                },
            )
            return
        self._send(404, {"error": "not-found"})

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path not in ("/otp/send", "/otp/verify"):
            self._send(404, {"error": "not-found"})
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length > MAX_BODY:
            self._send(413, {"error": "too-large"})
            return
        if length:
            # Drain and discard the body.
            self.rfile.read(length)
        status = authorize(self.secret, self.headers.get(HEADER))
        if status == 401:
            self._send(401, {"error": "unauthorized"})
            return
        self._send(
            503,
            {"error": "stub", "otp": "unavailable", "issue": "107"},
        )

    def do_PUT(self) -> None:  # noqa: N802
        self._send(405, {"error": "method-not-allowed"})

    def do_DELETE(self) -> None:  # noqa: N802
        self._send(405, {"error": "method-not-allowed"})


def main() -> None:
    secret = usable_secret(os.environ.get("SMS_OTP_SHARED_SECRET"))
    host = os.environ.get("SMS_STUB_BIND", "0.0.0.0")
    port = int(os.environ.get("SMS_STUB_PORT", "8080"))
    StubHandler.secret = secret
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
