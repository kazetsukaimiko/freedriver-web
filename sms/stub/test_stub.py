#!/usr/bin/env python3
"""Lock the sms stub: listen, reject a bad secret, never succeed send/verify."""

from __future__ import annotations

import json
import os
import threading
import urllib.error
import urllib.request

import server


def fail(message: str) -> None:
    raise SystemExit(message)


def request(base: str, method: str, path: str, header: str | None = None, body: bytes | None = None):
    req = urllib.request.Request(base + path, data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if header is not None:
        req.add_header(server.HEADER, header)
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()


def assert_status(status: int, expected: int, label: str) -> None:
    if status != expected:
        fail(f"{label}: expected {expected}, got {status}")


def start(secret: str | None) -> tuple[server.ThreadingHTTPServer, str]:
    if secret is None:
        os.environ.pop("SMS_OTP_SHARED_SECRET", None)
    else:
        os.environ["SMS_OTP_SHARED_SECRET"] = secret
    handler = server.StubHandler
    handler.secret = server.usable_secret(secret)
    httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    host, port = httpd.server_address[:2]
    return httpd, f"http://{host}:{port}"


def exercise(base: str, secret: str | None) -> None:
    status, body = request(base, "GET", "/health")
    assert_status(status, 503, "health")
    payload = json.loads(body)
    if payload.get("status") != "stub":
        fail("health body must identify the stub")

    sample = json.dumps({"phone": "+15555550100"}).encode()
    status, _ = request(base, "POST", "/otp/send", body=sample)
    assert_status(status, 401, "send without secret")
    status, _ = request(base, "POST", "/otp/verify", header="nope", body=sample)
    assert_status(status, 401, "verify wrong secret")
    if secret:
        status, matched = request(base, "POST", "/otp/send", header=secret, body=sample)
        assert_status(status, 503, "send with matching secret")
        if b"sent" in matched:
            fail("stub must not claim a code was sent")
        status, _ = request(
            base,
            "POST",
            "/otp/verify",
            header=secret,
            body=json.dumps({"phone": "+15555550100", "code": "000000"}).encode(),
        )
        assert_status(status, 503, "verify with matching secret")
    status, _ = request(base, "POST", "/otp/send", header=server.PLACEHOLDER, body=sample)
    assert_status(status, 401, "placeholder header")


def main() -> None:
    if server.usable_secret(None) is not None:
        fail("missing secret must be unusable")
    if server.usable_secret("  ") is not None:
        fail("blank secret must be unusable")
    if server.usable_secret(server.PLACEHOLDER) is not None:
        fail("placeholder must be unusable")
    if server.usable_secret("  " + server.PLACEHOLDER + " ") is not None:
        fail("padded placeholder must be unusable")
    if server.usable_secret("real-secret") != "real-secret":
        fail("real secret should be kept")
    if server.authorize(None, "x") != 401:
        fail("missing secret authorizes nothing")
    if server.authorize("real-secret", None) != 401:
        fail("missing header authorizes nothing")
    if server.authorize("real-secret", "other") != 401:
        fail("wrong header authorizes nothing")
    if server.authorize("real-secret", "real-secret") != 503:
        fail("matching secret must still fail closed")

    live, base = start("real-secret")
    try:
        exercise(base, "real-secret")
    finally:
        live.shutdown()

    missing, base = start(server.PLACEHOLDER)
    try:
        exercise(base, None)
    finally:
        missing.shutdown()

    print("sms stub fail-closed")


if __name__ == "__main__":
    main()
