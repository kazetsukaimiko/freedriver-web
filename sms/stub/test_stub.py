#!/usr/bin/env python3
"""Tests for the sms stub: shared-secret check, OtpService rules, and the HTTP contract."""

from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request

import server

KNOWN = "+15555550100"
UNKNOWN = "+15555550199"


def fail(message: str) -> None:
    raise SystemExit(message)


def check(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class Outbox:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    def __call__(self, phone: str, code: str) -> None:
        self.sent.append((phone, code))

    def last_code(self) -> str:
        return self.sent[-1][1]


def service() -> tuple[server.OtpService, Clock, Outbox]:
    clock = Clock()
    outbox = Outbox()
    return server.OtpService({KNOWN: "house.user"}, outbox, clock), clock, outbox


def wrong_code(code: str) -> str:
    return "%06d" % ((int(code) + 1) % 1_000_000)


def test_secret() -> None:
    check(server.usable_secret(None) is None, "missing secret must be unusable")
    check(server.usable_secret("  ") is None, "blank secret must be unusable")
    check(server.usable_secret(server.PLACEHOLDER) is None, "placeholder must be unusable")
    check(server.usable_secret("  " + server.PLACEHOLDER + " ") is None, "padded placeholder must be unusable")
    check(server.usable_secret("real-secret") == "real-secret", "real secret should be kept")
    check(not server.authorize(None, "x"), "missing secret authorizes nothing")
    check(not server.authorize("real-secret", None), "missing header authorizes nothing")
    check(not server.authorize("real-secret", "other"), "wrong header authorizes nothing")
    check(server.authorize("real-secret", "real-secret"), "matching header authorizes")


def test_unknown_numbers_look_like_known_ones() -> None:
    otp, _, outbox = service()
    check(otp.send(KNOWN) == otp.send(UNKNOWN) == (200, server.SENT), "send answers sent for every number")
    check([phone for phone, _ in outbox.sent] == [KNOWN], "only provisioned numbers get a text")
    check(otp.verify(UNKNOWN, "123456") == (400, server.INVALID_CODE), "unknown number has no code")


def test_code_signs_in_once() -> None:
    otp, _, outbox = service()
    otp.send(KNOWN)
    code = outbox.last_code()
    check(server.CODE_RE.match(code) is not None, "code is 6 digits")
    check(otp.verify(KNOWN, code) == (200, {"username": "house.user"}), "correct code returns the username")
    check(otp.verify(KNOWN, code) == (400, server.INVALID_CODE), "a code works once")


def test_code_expires() -> None:
    otp, clock, outbox = service()
    otp.send(KNOWN)
    clock.now += server.CODE_TTL_SECONDS
    check(otp.verify(KNOWN, outbox.last_code()) == (400, server.INVALID_CODE), "expired code fails")


def test_wrong_codes_per_phone() -> None:
    otp, clock, outbox = service()
    otp.send(KNOWN)
    code = outbox.last_code()
    for _ in range(server.MAX_WRONG_CODES_PER_WINDOW):
        check(otp.verify(KNOWN, wrong_code(code)) == (400, server.INVALID_CODE), "wrong code fails")
    check(otp.verify(KNOWN, code) == (400, server.INVALID_CODE), "limit drops the pending code")
    otp.send(KNOWN)
    check(otp.verify(KNOWN, outbox.last_code()) == (400, server.INVALID_CODE), "limit holds for the window")
    clock.now += server.WINDOW_SECONDS
    otp.send(KNOWN)
    check(otp.verify(KNOWN, outbox.last_code())[0] == 200, "window end lifts the limit")


def test_sends_per_phone() -> None:
    otp, clock, _ = service()
    for phone in (KNOWN, UNKNOWN):
        for _ in range(server.MAX_SENDS_PER_WINDOW):
            check(otp.send(phone) == (200, server.SENT), "send within the limit")
        check(otp.send(phone) == (429, server.RATE_LIMITED), "send limit applies to %s" % phone)
    clock.now += server.WINDOW_SECONDS
    check(otp.send(KNOWN) == (200, server.SENT), "window end lifts the send limit")


def test_without_sender() -> None:
    otp = server.OtpService({KNOWN: "house.user"})
    check(not otp.ready(), "service without a sender is not ready")
    check(otp.send(KNOWN) == otp.send(UNKNOWN) == (503, server.STUB), "send answers 503 for every number")
    check(otp.verify(KNOWN, "123456") == (503, server.STUB), "verify answers 503")
    check(otp.send("5555550100") == (400, server.BAD_REQUEST), "malformed phone is a bad request")


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


def start(secret: str | None, otp: server.OtpService) -> tuple[server.ThreadingHTTPServer, str]:
    handler = type("Handler", (server.StubHandler,), {"secret": server.usable_secret(secret), "service": otp})
    httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    host, port = httpd.server_address[:2]
    return httpd, f"http://{host}:{port}"


def phone_body(phone: str) -> bytes:
    return json.dumps({"phone": phone}).encode()


def test_http_stub() -> None:
    httpd, base = start("real-secret", server.OtpService())
    try:
        status, body = request(base, "GET", "/health")
        check(status == 503 and json.loads(body).get("status") == "stub", "stub health is 503")
        check(request(base, "POST", "/otp/send", body=phone_body(KNOWN))[0] == 401, "send without secret")
        check(request(base, "POST", "/otp/verify", "nope", phone_body(KNOWN))[0] == 401, "verify wrong secret")
        check(request(base, "POST", "/otp/send", server.PLACEHOLDER, phone_body(KNOWN))[0] == 401, "placeholder header")
        status, body = request(base, "POST", "/otp/send", "real-secret", phone_body(KNOWN))
        check(status == 503 and b"sent" not in body, "stub send answers 503")
    finally:
        httpd.shutdown()

    for secret in ("", server.PLACEHOLDER):
        otp, _, _ = service()
        httpd, base = start(secret, otp)
        try:
            for header in ("", server.PLACEHOLDER, "real-secret"):
                for path in ("/otp/send", "/otp/verify"):
                    status = request(base, "POST", path, header, phone_body(KNOWN))[0]
                    check(status == 401, "secret %r answers 401 on %s" % (secret, path))
        finally:
            httpd.shutdown()


def test_http_with_sender() -> None:
    otp, _, outbox = service()
    httpd, base = start("real-secret", otp)
    try:
        check(request(base, "GET", "/health")[0] == 200, "health is 200 with a sender")
        known = request(base, "POST", "/otp/send", "real-secret", phone_body(KNOWN))
        unknown = request(base, "POST", "/otp/send", "real-secret", phone_body(UNKNOWN))
        check(known == unknown == (200, b'{"status": "sent"}'), "send response is identical for unknown numbers")
        code = outbox.last_code()
        status, body = request(
            base, "POST", "/otp/verify", "real-secret", json.dumps({"phone": KNOWN, "code": wrong_code(code)}).encode()
        )
        check((status, json.loads(body)) == (400, server.INVALID_CODE), "wrong code is 400 invalid-code")
        status, body = request(
            base, "POST", "/otp/verify", "real-secret", json.dumps({"phone": KNOWN, "code": code}).encode()
        )
        check((status, json.loads(body)) == (200, {"username": "house.user"}), "correct code returns the username")
        check(request(base, "POST", "/otp/send", "real-secret", b"[1]")[0] == 400, "non-object body is a bad request")
    finally:
        httpd.shutdown()


def main() -> None:
    for test in (
        test_secret,
        test_unknown_numbers_look_like_known_ones,
        test_code_signs_in_once,
        test_code_expires,
        test_wrong_codes_per_phone,
        test_sends_per_phone,
        test_without_sender,
        test_http_stub,
        test_http_with_sender,
    ):
        test()
    print("sms stub ok")


if __name__ == "__main__":
    main()
