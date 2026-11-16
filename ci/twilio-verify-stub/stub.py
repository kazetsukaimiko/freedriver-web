#!/usr/bin/env python3
"""Twilio Verify HTTP stub for the compose smoke job.

The production Twilio sender posts here. The smoke test reads the generated
code from GET /_code.
"""
from __future__ import annotations

import json
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
# phone -> {sid, code}
CODES: dict[str, dict[str, str]] = {}
LAST: dict[str, str] | None = None


def _sid() -> str:
    return "VE" + secrets.token_hex(16)


def _json(handler: BaseHTTPRequestHandler, status: int, payload: dict) -> None:
    body = json.dumps(payload).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _form(handler: BaseHTTPRequestHandler) -> dict[str, str]:
    length = int(handler.headers.get("Content-Length") or "0")
    raw = handler.rfile.read(length).decode("utf-8") if length else ""
    parsed = parse_qs(raw, keep_blank_values=True)
    return {key: values[-1] for key, values in parsed.items()}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        print("twilio-stub:", fmt % args, flush=True)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            _json(self, 200, {"ok": True})
            return
        if parsed.path == "/_code":
            query = parse_qs(parsed.query)
            to = (query.get("to") or [None])[0]
            with LOCK:
                record = CODES.get(to) if to else LAST
            if not record:
                _json(self, 404, {"error": "no-code"})
                return
            _json(self, 200, record)
            return
        _json(self, 404, {"error": "not-found"})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        parts = [p for p in parsed.path.split("/") if p]
        # /v2/Services/{sid}/Verifications
        # /v2/Services/{sid}/VerificationCheck
        # /v2/Services/{sid}/Verifications/{veSid}
        if len(parts) < 4 or parts[0] != "v2" or parts[1] != "Services":
            _json(self, 404, {"error": "not-found"})
            return
        form = _form(self)
        if len(parts) == 4 and parts[3] == "Verifications":
            to = form.get("To") or ""
            code = f"{secrets.randbelow(1_000_000):06d}"
            sid = _sid()
            record = {"to": to, "code": code, "sid": sid, "status": "pending"}
            with LOCK:
                CODES[to] = record
                global LAST
                LAST = record
            _json(self, 201, {"sid": sid, "status": "pending"})
            return
        if len(parts) == 4 and parts[3] == "VerificationCheck":
            to = form.get("To") or ""
            code = form.get("Code") or ""
            with LOCK:
                record = CODES.get(to)
                if record and record.get("code") == code:
                    record["status"] = "approved"
                    CODES.pop(to, None)
                    _json(self, 200, {"status": "approved", "sid": record["sid"]})
                    return
            _json(self, 200, {"status": "pending"})
            return
        if len(parts) == 5 and parts[3] == "Verifications":
            sid = parts[4]
            with LOCK:
                for phone, record in list(CODES.items()):
                    if record.get("sid") == sid:
                        record["status"] = form.get("Status") or "canceled"
                        CODES.pop(phone, None)
                        break
            _json(self, 200, {"sid": sid, "status": form.get("Status") or "canceled"})
            return
        _json(self, 404, {"error": "not-found"})


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", 8080), Handler)
    print("twilio-stub listening on 8080", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
