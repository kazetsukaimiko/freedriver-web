#!/usr/bin/env python3
"""Check a Keycloak browser-flow copy keeps password REQUIRED and SMS optional.

Reads a kcadm executions JSON array from a file or stdin.
Exit 0 when the password authenticator is REQUIRED inside a top-level
ALTERNATIVE subflow and SMS OTP is ALTERNATIVE or DISABLED.
--require-sms also requires exactly one SMS execution, set to ALTERNATIVE.
"""

from __future__ import annotations

import argparse
import json
import sys

PASSWORD = "auth-username-password-form"
SMS = "freedriver-sms-otp"


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def load(path: str | None):
    raw = sys.stdin.read() if path is None else open(path, encoding="utf-8").read()
    data = json.loads(raw)
    if isinstance(data, dict) and "executions" in data:
        data = data["executions"]
    if not isinstance(data, list):
        fail("expected a list of authentication executions")
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?")
    parser.add_argument("--require-sms", action="store_true")
    args = parser.parse_args()
    executions = load(args.path)

    password = [e for e in executions if e.get("providerId") == PASSWORD]
    if not password:
        fail("password authenticator auth-username-password-form is missing")
    if any(e.get("requirement") == "DISABLED" for e in password):
        fail("password authenticator is DISABLED; it must be REQUIRED")
    if not any(e.get("requirement") == "REQUIRED" for e in password):
        fail("password authenticator must stay REQUIRED inside the forms Alternative")

    def parent_flow(index: int):
        level = executions[index].get("level", 0)
        for earlier in range(index - 1, -1, -1):
            candidate = executions[earlier]
            if candidate.get("authenticationFlow") and candidate.get("level", 0) < level:
                return candidate
        return None

    for index, execution in enumerate(executions):
        if execution.get("providerId") != PASSWORD:
            continue
        parent = parent_flow(index)
        if parent is None or parent.get("requirement") != "ALTERNATIVE":
            fail("password authenticator must stay inside a top-level forms Alternative")

    sms = [e for e in executions if e.get("providerId") == SMS]
    if any(e.get("requirement") == "REQUIRED" for e in sms):
        fail("SMS OTP is REQUIRED; set it to ALTERNATIVE so password stays available")
    if args.require_sms:
        if len(sms) != 1:
            fail("expected exactly one freedriver-sms-otp execution")
        if sms[0].get("requirement") != "ALTERNATIVE":
            fail("SMS OTP must be ALTERNATIVE, found %s" % sms[0].get("requirement"))

    print("password Alternative preserved")


if __name__ == "__main__":
    main()
