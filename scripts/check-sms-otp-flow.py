#!/usr/bin/env python3
"""Check the signed browser-freedriver phone/username flow.

auth-cookie is the first top-level ALTERNATIVE. One top-level ALTERNATIVE
subflow holds the router (REQUIRED), then a phone CONDITIONAL subflow and a
username CONDITIONAL subflow. The check fails if auth-cookie is missing or if
freedriver-phone-username-router is ahead of it.

The phone grammar is the seven US forms plus the canonical +1 and ten digits
from #155. It matches any US ten-digit number, not one fixed digit string.
A longer string that only contains those digits is not a phone.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

COOKIE = "auth-cookie"
ROUTER = "freedriver-phone-username-router"
CONDITION = "freedriver-sms-route-condition"
SMS = "freedriver-sms-otp"
PASSWORD = "auth-username-password-form"

TOP = "freedriver-phone-username"
PHONE = "freedriver-phone-path"
USER = "freedriver-username-path"
MFA = "freedriver-username-2fa"

COOKIE_PRIORITY = 0
DISABLED_PRIORITY = 10
TOP_PRIORITY = 40
ROUTER_PRIORITY = 10
PHONE_PRIORITY = 20
USER_PRIORITY = 30
CONDITION_PRIORITY = 10
SMS_PRIORITY = 20
PASSWORD_PRIORITY = 20
MFA_PRIORITY = 40

# Whole-string forms. [0-9]{10} is any ten digits, not a single example.
PHONE_FORMS = (
    r"[0-9]{3}-[0-9]{3}-[0-9]{4}",
    r"[0-9]{10}",
    r"\([0-9]{3}\) [0-9]{3}-[0-9]{4}",
    r"[0-9]{3}\.[0-9]{3}\.[0-9]{4}",
    r"[0-9]{3} [0-9]{3} [0-9]{4}",
    r"1-[0-9]{3}-[0-9]{3}-[0-9]{4}",
    r"\+1 [0-9]{3} [0-9]{3} [0-9]{4}",
    r"\+1[0-9]{10}",
)

USERNAME_RULE_MESSAGE = "Choose a username that is not a phone number."

DESCRIPTIONS = {
    TOP: "Phone or username sign-in. auth-cookie stays ahead of this subflow.",
    PHONE: "Phone path when the route note is phone.",
    USER: "Username path. The password form is required on this path.",
    MFA: "Conditional second factor kept behind the password form.",
}


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def phone_alternation() -> str:
    return "(?:%s)" % "|".join(PHONE_FORMS)


def is_phone_username(value: object) -> bool:
    if not isinstance(value, str):
        return False
    return re.fullmatch(phone_alternation(), value) is not None


def username_reject_pattern() -> str:
    """Java Matcher.matches() pattern: anything that is not a phone form."""
    return "(?!(?:%s)$).*" % "|".join(PHONE_FORMS)


def load(path: str | None):
    raw = sys.stdin.read() if path is None else Path(path).read_text(encoding="utf-8")
    data = json.loads(raw)
    if isinstance(data, dict) and "executions" in data:
        data = data["executions"]
    if not isinstance(data, list):
        fail("expected a list of authentication executions")
    return data


def flow_name(execution: dict) -> str:
    if not execution.get("authenticationFlow"):
        return ""
    return execution.get("displayName") or execution.get("alias") or ""


def parents_of(executions: list) -> list:
    parents: list[int | None] = []
    for index, execution in enumerate(executions):
        level = execution.get("level", 0)
        parent = None
        if level:
            for earlier in range(index - 1, -1, -1):
                candidate = executions[earlier]
                if candidate.get("authenticationFlow") and candidate.get("level", 0) == level - 1:
                    parent = earlier
                    break
        parents.append(parent)
    return parents


def children_of(parents: list, index: int) -> list[int]:
    return [child for child, parent in enumerate(parents) if parent == index]


def route_value(config: object, config_key: str):
    if not isinstance(config, dict) or not config:
        return None
    if config_key in config:
        return config.get(config_key)
    if len(config) == 1:
        return next(iter(config.values()))
    return None


def wanted_flags(executions: list, parents: list) -> list[bool]:
    wanted = [False] * len(executions)

    def mark(index: int) -> None:
        if index < 0 or wanted[index]:
            return
        wanted[index] = True

    cookie = None
    for index, execution in enumerate(executions):
        if execution.get("level", 0) == 0 and execution.get("providerId") == COOKIE:
            cookie = index
            break
    if cookie is not None:
        mark(cookie)

    for index, execution in enumerate(executions):
        if execution.get("level", 0) != 0:
            continue
        if execution.get("authenticationFlow"):
            continue
        if execution.get("requirement") == "DISABLED":
            mark(index)

    top = None
    for index, execution in enumerate(executions):
        if execution.get("level", 0) == 0 and flow_name(execution) == TOP:
            top = index
            break
    if top is None:
        return wanted
    mark(top)

    def first_child(parent: int, predicate) -> int | None:
        for child in children_of(parents, parent):
            if predicate(executions[child]):
                return child
        return None

    router = first_child(top, lambda e: e.get("providerId") == ROUTER)
    phone = first_child(top, lambda e: flow_name(e) == PHONE)
    user = first_child(top, lambda e: flow_name(e) == USER)
    for index in (router, phone, user):
        if index is not None:
            mark(index)
    if phone is not None:
        cond = first_child(phone, lambda e: e.get("providerId") == CONDITION)
        sms = first_child(phone, lambda e: e.get("providerId") == SMS)
        for index in (cond, sms):
            if index is not None:
                mark(index)
    if user is not None:
        cond = first_child(user, lambda e: e.get("providerId") == CONDITION)
        password = first_child(user, lambda e: e.get("providerId") == PASSWORD)
        mfa = first_child(user, lambda e: flow_name(e) == MFA)
        for index in (cond, password, mfa):
            if index is not None:
                mark(index)
        if mfa is not None:
            for index in range(len(executions)):
                parent = parents[index]
                while parent is not None:
                    if parent == mfa:
                        mark(index)
                        break
                    parent = parents[parent]
    return wanted


def subtree_root(index: int, parents: list, wanted: list[bool]) -> int:
    while parents[index] is not None and not wanted[parents[index]]:
        index = parents[index]
    return index


def find_mfa_source(executions: list, parents: list, wanted: list[bool]) -> int | None:
    for index, execution in enumerate(executions):
        if execution.get("providerId") != PASSWORD:
            continue
        parent = parents[index]
        if parent is None or flow_name(executions[parent]) == USER:
            continue
        for child in children_of(parents, parent):
            candidate = executions[child]
            if not candidate.get("authenticationFlow") or wanted[child]:
                continue
            if flow_name(candidate) in (TOP, PHONE, USER, MFA):
                continue
            return child
    return None


def plan_next(executions: list, root_flow: str, config_key: str) -> dict:
    parents = parents_of(executions)
    wanted = wanted_flags(executions, parents)

    def delete(index: int) -> dict:
        return {"op": "delete", "id": executions[index]["id"]}

    for alias in (TOP, PHONE, USER, MFA):
        for index, execution in enumerate(executions):
            if flow_name(execution) == alias and not wanted[index]:
                return delete(subtree_root(index, parents, wanted))

    def find_wanted(alias: str | None = None, provider: str | None = None, parent: int | None = None):
        for index, execution in enumerate(executions):
            if not wanted[index]:
                continue
            if parent is not None and parents[index] != parent:
                continue
            if alias is not None and flow_name(execution) != alias:
                continue
            if provider is not None and execution.get("providerId") != provider:
                continue
            if alias is None and provider is None and parent is None:
                continue
            return index
        return None

    if find_wanted(provider=COOKIE) is None:
        return {"op": "create-execution", "parent": root_flow, "provider": COOKIE}

    top = find_wanted(alias=TOP)
    if top is None:
        return {
            "op": "create-flow",
            "parent": root_flow,
            "alias": TOP,
            "description": DESCRIPTIONS[TOP],
        }

    if find_wanted(provider=ROUTER, parent=top) is None:
        return {"op": "create-execution", "parent": TOP, "provider": ROUTER}

    phone = find_wanted(alias=PHONE, parent=top)
    if phone is None:
        return {
            "op": "create-flow",
            "parent": TOP,
            "alias": PHONE,
            "description": DESCRIPTIONS[PHONE],
        }
    if find_wanted(provider=CONDITION, parent=phone) is None:
        return {"op": "create-execution", "parent": PHONE, "provider": CONDITION}
    if find_wanted(provider=SMS, parent=phone) is None:
        return {"op": "create-execution", "parent": PHONE, "provider": SMS}

    user = find_wanted(alias=USER, parent=top)
    if user is None:
        return {
            "op": "create-flow",
            "parent": TOP,
            "alias": USER,
            "description": DESCRIPTIONS[USER],
        }
    if find_wanted(provider=CONDITION, parent=user) is None:
        return {"op": "create-execution", "parent": USER, "provider": CONDITION}
    if find_wanted(provider=PASSWORD, parent=user) is None:
        return {"op": "create-execution", "parent": USER, "provider": PASSWORD}

    def config_action(index: int, expected: str, alias: str) -> dict | None:
        execution = executions[index]
        if "config" not in execution and execution.get("authenticationConfig"):
            return {
                "op": "error",
                "message": "execution config was not loaded for %s" % execution.get("id"),
            }
        if route_value(execution.get("config"), config_key) == expected:
            return None
        return {
            "op": "set-config",
            "executionId": execution["id"],
            "configId": execution.get("authenticationConfig"),
            "alias": execution.get("configAlias") or alias,
            "config": {config_key: expected},
        }

    phone_cond = find_wanted(provider=CONDITION, parent=phone)
    action = config_action(phone_cond, "phone", "freedriver-route-phone")
    if action:
        return action
    user_cond = find_wanted(provider=CONDITION, parent=user)
    action = config_action(user_cond, "username", "freedriver-route-username")
    if action:
        return action

    source = find_mfa_source(executions, parents, wanted)
    mfa = find_wanted(alias=MFA, parent=user)
    if source is not None:
        if mfa is None:
            return {
                "op": "create-flow",
                "parent": USER,
                "alias": MFA,
                "description": DESCRIPTIONS[MFA],
            }
        source_children = children_of(parents, source)
        for child in source_children:
            if executions[child].get("authenticationFlow"):
                return {
                    "op": "error",
                    "message": "conditional second factor has a nested flow; not deleting it",
                }
        # Keycloak lists executions by priority. A new conditional-user-configured
        # step keeps priority 10 while the steps added after it get 1, 2, 3, so
        # list order is not copy order. Match on providerId, then set priority.
        dest_by_provider: dict[str, list[int]] = {}
        for child in children_of(parents, mfa):
            dest_by_provider.setdefault(executions[child].get("providerId"), []).append(child)
        for source_index in source_children:
            provider = executions[source_index].get("providerId")
            if not dest_by_provider.get(provider):
                return {
                    "op": "create-execution",
                    "parent": MFA,
                    "provider": provider,
                }
        for offset, source_index in enumerate(source_children):
            source_exec = executions[source_index]
            matches = dest_by_provider.get(source_exec.get("providerId")) or []
            if len(matches) != 1:
                return {
                    "op": "error",
                    "message": "second factor child does not match the copied flow",
                }
            dest_exec = executions[matches[0]]
            desired_priority = (offset + 1) * 10
            if (
                dest_exec.get("requirement") != source_exec.get("requirement")
                or dest_exec.get("priority") != desired_priority
            ):
                return {
                    "op": "set-execution",
                    "flow": root_flow,
                    "id": dest_exec["id"],
                    "requirement": source_exec.get("requirement"),
                    "priority": desired_priority,
                }
            if source_exec.get("authenticationConfig") and "config" not in source_exec:
                return {
                    "op": "error",
                    "message": "execution config was not loaded for the second factor",
                }
            source_config = source_exec.get("config") or {}
            if source_config and source_exec.get("config") != dest_exec.get("config"):
                return {
                    "op": "set-config",
                    "executionId": dest_exec["id"],
                    "configId": dest_exec.get("authenticationConfig"),
                    "alias": dest_exec.get("configAlias")
                    or "freedriver-2fa-%s-%s" % (source_exec.get("providerId"), offset),
                    "config": source_config,
                }
        source_exec = executions[source]
        mfa_exec = executions[mfa]
        if (
            mfa_exec.get("requirement") != source_exec.get("requirement")
            or mfa_exec.get("priority") != MFA_PRIORITY
        ):
            return {
                "op": "set-execution",
                "flow": root_flow,
                "id": mfa_exec["id"],
                "requirement": source_exec.get("requirement"),
                "priority": MFA_PRIORITY,
            }
    elif mfa is not None and executions[mfa].get("priority") != MFA_PRIORITY:
        return {
            "op": "set-execution",
            "flow": root_flow,
            "id": executions[mfa]["id"],
            "requirement": executions[mfa].get("requirement"),
            "priority": MFA_PRIORITY,
        }

    for index, execution in enumerate(executions):
        if wanted[index]:
            continue
        return delete(subtree_root(index, parents, wanted))

    expected = []
    cookie = find_wanted(provider=COOKIE)
    if cookie is not None and parents[cookie] is None:
        expected.append((cookie, "ALTERNATIVE", COOKIE_PRIORITY))
    for index, execution in enumerate(executions):
        if (
            wanted[index]
            and parents[index] is None
            and not execution.get("authenticationFlow")
            and execution.get("requirement") == "DISABLED"
            and execution.get("priority", 0) < DISABLED_PRIORITY
        ):
            expected.append((index, "DISABLED", DISABLED_PRIORITY))
    expected.append((top, "ALTERNATIVE", TOP_PRIORITY))
    expected.append((find_wanted(provider=ROUTER, parent=top), "REQUIRED", ROUTER_PRIORITY))
    expected.append((phone, "CONDITIONAL", PHONE_PRIORITY))
    expected.append((phone_cond, "REQUIRED", CONDITION_PRIORITY))
    expected.append((find_wanted(provider=SMS, parent=phone), "ALTERNATIVE", SMS_PRIORITY))
    expected.append((user, "CONDITIONAL", USER_PRIORITY))
    expected.append((user_cond, "REQUIRED", CONDITION_PRIORITY))
    expected.append((find_wanted(provider=PASSWORD, parent=user), "REQUIRED", PASSWORD_PRIORITY))
    for index, requirement, priority in expected:
        execution = executions[index]
        if execution.get("requirement") != requirement or execution.get("priority") != priority:
            return {
                "op": "set-execution",
                "flow": root_flow,
                "id": execution["id"],
                "requirement": requirement,
                "priority": priority,
            }
    return {"op": "done"}


def validate(executions: list, config_key: str) -> None:
    parents = parents_of(executions)
    cookies = [
        i
        for i, execution in enumerate(executions)
        if execution.get("providerId") == COOKIE and execution.get("level", 0) == 0
    ]
    if not cookies:
        fail("auth-cookie is missing")
    cookie = cookies[0]
    routers = [i for i, execution in enumerate(executions) if execution.get("providerId") == ROUTER]
    if any(index < cookie for index in routers):
        fail("router is ahead of auth-cookie")
    if executions[cookie].get("requirement") != "ALTERNATIVE":
        fail("auth-cookie must be the first top-level ALTERNATIVE")
    top_alternatives = [
        i
        for i, execution in enumerate(executions)
        if execution.get("level", 0) == 0 and execution.get("requirement") == "ALTERNATIVE"
    ]
    if not top_alternatives or top_alternatives[0] != cookie:
        fail("auth-cookie must be the first top-level ALTERNATIVE")
    if not routers:
        fail("freedriver-phone-username-router is missing")

    tops = [
        i
        for i, execution in enumerate(executions)
        if execution.get("level", 0) == 0
        and execution.get("authenticationFlow")
        and execution.get("requirement") == "ALTERNATIVE"
    ]
    if len(tops) != 1 or flow_name(executions[tops[0]]) != TOP:
        fail("expected one top-level ALTERNATIVE subflow freedriver-phone-username")
    top = tops[0]
    if top < cookie:
        fail("router is ahead of auth-cookie")
    for index in range(cookie + 1, top):
        execution = executions[index]
        if execution.get("level", 0) == 0 and execution.get("requirement") != "DISABLED":
            fail("an execution that can challenge is ahead of the phone-username subflow")
    for index, execution in enumerate(executions):
        if execution.get("level", 0) != 0 or index in (cookie, top):
            continue
        if execution.get("authenticationFlow") or execution.get("requirement") != "DISABLED":
            fail("only auth-cookie and one subflow may run at the top level")

    def child_named(parent: int, name: str) -> int:
        found = [i for i in children_of(parents, parent) if flow_name(executions[i]) == name]
        if len(found) != 1:
            fail("missing subflow %s" % name)
        return found[0]

    def child_provider(parent: int, provider: str) -> list[int]:
        return [i for i in children_of(parents, parent) if executions[i].get("providerId") == provider]

    router_ids = child_provider(top, ROUTER)
    if len(router_ids) != 1 or executions[router_ids[0]].get("requirement") != "REQUIRED":
        fail("router must be REQUIRED inside the top-level subflow")
    router = router_ids[0]
    if executions[router].get("level", 0) == 0:
        fail("router must not be a top-level execution")

    phone = child_named(top, PHONE)
    user = child_named(top, USER)
    if executions[phone].get("requirement") != "CONDITIONAL":
        fail("phone path must be a CONDITIONAL subflow")
    if executions[user].get("requirement") != "CONDITIONAL":
        fail("username path must be a CONDITIONAL subflow")
    if not (router < phone < user):
        fail("phone path must follow the router and precede the username path")

    top_children = children_of(parents, top)
    allowed_top = {router, phone, user}
    if any(child not in allowed_top for child in top_children):
        fail("top-level subflow holds an unexpected execution")

    phone_conds = child_provider(phone, CONDITION)
    phone_sms = child_provider(phone, SMS)
    if len(phone_conds) != 1 or len(phone_sms) != 1:
        fail("phone path must contain the route condition and one freedriver-sms-otp execution")
    if executions[phone_conds[0]].get("requirement") != "REQUIRED":
        fail("phone route condition must be REQUIRED")
    if route_value(executions[phone_conds[0]].get("config"), config_key) != "phone":
        fail("phone condition must be configured as phone")
    if phone_conds[0] > phone_sms[0]:
        fail("phone route condition must come before freedriver-sms-otp")
    if executions[phone_sms[0]].get("requirement") != "ALTERNATIVE":
        fail("freedriver-sms-otp must not be REQUIRED")
    if any(child not in (phone_conds[0], phone_sms[0]) for child in children_of(parents, phone)):
        fail("phone path holds an unexpected execution")

    user_conds = child_provider(user, CONDITION)
    user_passwords = child_provider(user, PASSWORD)
    if len(user_conds) != 1:
        fail("username path must contain the route condition")
    if executions[user_conds[0]].get("requirement") != "REQUIRED":
        fail("username route condition must be REQUIRED")
    if route_value(executions[user_conds[0]].get("config"), config_key) != "username":
        fail("username condition must be configured as username")

    passwords = [i for i, execution in enumerate(executions) if execution.get("providerId") == PASSWORD]
    if not passwords:
        fail("password authenticator auth-username-password-form is missing")
    if any(executions[i].get("requirement") == "DISABLED" for i in passwords):
        fail("password authenticator is DISABLED; it must be REQUIRED")
    if any(executions[i].get("requirement") != "REQUIRED" for i in passwords):
        fail("password authenticator must be REQUIRED on the username path")
    if user_passwords != passwords:
        fail("password authenticator must be REQUIRED inside the username subflow")
    password = user_passwords[0]
    if user_conds[0] > password:
        fail("username route condition must come before the password form")

    mfa_ids = [i for i in children_of(parents, user) if flow_name(executions[i]) == MFA]
    if len(mfa_ids) > 1:
        fail("username path has more than one second-factor subflow")
    if mfa_ids and mfa_ids[0] < password:
        fail("second factor is ahead of the password form")
    allowed_user = {user_conds[0], password, *mfa_ids}
    if any(child not in allowed_user for child in children_of(parents, user)):
        fail("username path holds an unexpected execution")

    sms = [i for i, execution in enumerate(executions) if execution.get("providerId") == SMS]
    if len(sms) != 1 or sms[0] != phone_sms[0]:
        fail("expected exactly one freedriver-sms-otp execution, inside the phone path")
    if any(execution.get("requirement") == "REQUIRED" for execution in executions if execution.get("providerId") == SMS):
        fail("freedriver-sms-otp must not be REQUIRED")

    print("phone username flow shape ok")


def validate_browser_source(executions: list) -> None:
    cookies = [
        e
        for e in executions
        if e.get("providerId") == COOKIE and e.get("level", 0) == 0 and e.get("requirement") == "ALTERNATIVE"
    ]
    if not cookies:
        fail("auth-cookie is missing")
    passwords = [e for e in executions if e.get("providerId") == PASSWORD]
    if not passwords:
        fail("password authenticator auth-username-password-form is missing")
    if any(e.get("requirement") == "DISABLED" for e in passwords):
        fail("password authenticator is DISABLED; it must be REQUIRED")
    if not any(e.get("requirement") == "REQUIRED" for e in passwords):
        fail("password authenticator must be REQUIRED")
    print("browser source has cookie and password")


def count_phone_usernames(data) -> int:
    if isinstance(data, dict) and "users" in data:
        data = data["users"]
    if not isinstance(data, list):
        fail("expected a list of users")
    count = 0
    for item in data:
        if isinstance(item, str):
            name = item
        elif isinstance(item, dict):
            name = item.get("username")
        else:
            name = None
        if is_phone_username(name):
            count += 1
    return count


def apply_username_rule(profile: dict) -> bool:
    pattern = username_reject_pattern()
    wanted = {"pattern": pattern, "error-message": USERNAME_RULE_MESSAGE}
    attributes = profile.setdefault("attributes", [])
    username = next((attr for attr in attributes if attr.get("name") == "username"), None)
    if username is None:
        username = {"name": "username", "displayName": "${username}", "validations": {}}
        attributes.append(username)
    if not isinstance(username.get("validations"), dict):
        username["validations"] = {}
    validations = username["validations"]
    current = validations.get("pattern")
    if current == wanted:
        return False
    if isinstance(current, dict) and current.get("pattern") not in (None, pattern):
        fail("username attribute already has a different pattern validation")
    validations["pattern"] = wanted
    return True


def select_config_key(description: dict) -> str:
    properties = description.get("properties") or []
    names = [prop.get("name") for prop in properties if isinstance(prop, dict) and prop.get("name")]
    if "route" in names:
        return "route"
    if len(names) == 1:
        return names[0]
    fail(
        "freedriver-sms-route-condition config key must be route or its only property, found: %s"
        % ",".join(names)
    )
    return ""


def self_test() -> None:
    forms = (
        "{d0}{d1}{d2}-{d3}{d4}{d5}-{d6}{d7}{d8}{d9}",
        "{d0}{d1}{d2}{d3}{d4}{d5}{d6}{d7}{d8}{d9}",
        "({d0}{d1}{d2}) {d3}{d4}{d5}-{d6}{d7}{d8}{d9}",
        "{d0}{d1}{d2}.{d3}{d4}{d5}.{d6}{d7}{d8}{d9}",
        "{d0}{d1}{d2} {d3}{d4}{d5} {d6}{d7}{d8}{d9}",
        "1-{d0}{d1}{d2}-{d3}{d4}{d5}-{d6}{d7}{d8}{d9}",
        "+1 {d0}{d1}{d2} {d3}{d4}{d5} {d6}{d7}{d8}{d9}",
        "+1{d0}{d1}{d2}{d3}{d4}{d5}{d6}{d7}{d8}{d9}",
    )
    samples = ("1234567890", "2025550199", "9876543210")
    pattern = re.compile(username_reject_pattern())
    for sample in samples:
        digits = {("d%s" % i): sample[i] for i in range(10)}
        for form in forms:
            value = form.format(**digits)
            if not is_phone_username(value) or pattern.fullmatch(value):
                fail("phone grammar missed %s" % form)
        if is_phone_username(sample + "x") or is_phone_username("x" + sample):
            fail("a longer string must not match")
        if is_phone_username(sample + "1"):
            fail("eleven digits must not match")
    if is_phone_username("alice") or is_phone_username("") or is_phone_username("(202)555-0199"):
        fail("non-phone usernames must not match")
    if not pattern.fullmatch("alice"):
        fail("reject pattern must allow a normal username")
    users = [{"username": "alice"}, {"username": "2025550199"}, "123-456-7890", {"username": "kaze"}]
    if count_phone_usernames(users) != 2:
        fail("phone username count is wrong")

    root = Path(__file__).resolve().parent
    ok = load(str(root / "fixtures" / "sms-otp-flow-ok.json"))
    validate(ok, "route")
    if plan_next(ok, "browser-freedriver", "route").get("op") != "done":
        fail("signed fixture must not need another plan step")
    source = load(str(root / "fixtures" / "sms-otp-flow-password-only.json"))
    first = plan_next(source, "browser-freedriver", "route")
    if first.get("op") != "create-flow" or first.get("alias") != TOP:
        fail("planner must start by creating the phone-username subflow")
    no_cookie = [execution for execution in source if execution.get("providerId") != COOKIE]
    restore = plan_next(no_cookie, "browser-freedriver", "route")
    if restore.get("op") != "create-execution" or restore.get("provider") != COOKIE:
        fail("planner must restore auth-cookie before inserting the router")

    import io
    from contextlib import redirect_stderr

    def expect_failure(executions, text):
        buffer = io.StringIO()
        try:
            with redirect_stderr(buffer):
                validate(executions, "route")
        except SystemExit:
            if text not in buffer.getvalue():
                fail("expected failure %s" % text)
            return
        fail("expected failure %s" % text)

    ahead = load(str(root / "fixtures" / "sms-otp-flow-router-ahead.json"))
    expect_failure(ahead, "router is ahead of auth-cookie")
    missing = load(str(root / "fixtures" / "sms-otp-flow-cookie-missing.json"))
    expect_failure(missing, "auth-cookie is missing")

    mfa_source = json.loads(json.dumps(ok))
    mfa_source.extend(
        [
            {
                "id": "forms",
                "displayName": "browser-freedriver forms",
                "requirement": "ALTERNATIVE",
                "authenticationFlow": True,
                "level": 0,
                "priority": 50,
            },
            {
                "id": "old-pass",
                "providerId": PASSWORD,
                "requirement": "REQUIRED",
                "authenticationFlow": False,
                "level": 1,
                "priority": 10,
            },
            {
                "id": "old-2fa",
                "displayName": "Browser - Conditional 2FA",
                "requirement": "CONDITIONAL",
                "authenticationFlow": True,
                "level": 1,
                "priority": 20,
            },
            {
                "id": "old-cond",
                "providerId": "conditional-user-configured",
                "requirement": "REQUIRED",
                "authenticationFlow": False,
                "level": 2,
                "priority": 10,
            },
            {
                "id": "old-otp",
                "providerId": "auth-otp-form",
                "requirement": "ALTERNATIVE",
                "authenticationFlow": False,
                "level": 2,
                "priority": 20,
                "config": {"default": "skip"},
            },
        ]
    )
    cloned = plan_next(mfa_source, "browser-freedriver", "route")
    if cloned.get("op") != "create-flow" or cloned.get("alias") != MFA:
        fail("planner must keep the conditional second factor before deleting forms")
    reordered = json.loads(json.dumps(mfa_source))
    insert_at = next(index for index, execution in enumerate(reordered) if execution.get("id") == "forms")
    reordered[insert_at:insert_at] = [
        {
            "id": "new-2fa",
            "displayName": MFA,
            "requirement": "DISABLED",
            "authenticationFlow": True,
            "level": 2,
            "priority": 2,
        },
        {
            "id": "new-otp",
            "providerId": "auth-otp-form",
            "requirement": "DISABLED",
            "authenticationFlow": False,
            "level": 3,
            "priority": 1,
        },
        {
            "id": "new-cond",
            "providerId": "conditional-user-configured",
            "requirement": "REQUIRED",
            "authenticationFlow": False,
            "level": 3,
            "priority": 10,
        },
    ]
    repair = plan_next(reordered, "browser-freedriver", "route")
    if (
        repair.get("op") != "set-execution"
        or repair.get("id") != "new-otp"
        or repair.get("requirement") != "ALTERNATIVE"
        or repair.get("priority") != 20
    ):
        fail("planner must repair second-factor priority instead of stopping")
    profile = {"attributes": [{"name": "username", "validations": {"length": {"min": 3}}}]}
    if not apply_username_rule(profile):
        fail("username rule must be added")
    if apply_username_rule(profile):
        fail("username rule must be idempotent")
    kept = profile["attributes"][0]["validations"]["length"]["min"]
    if kept != 3:
        fail("username rule must leave other validations in place")
    print("sms otp flow self-test ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?")
    parser.add_argument("--require-sms", action="store_true")
    parser.add_argument("--browser-source", action="store_true")
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--flow", default="browser-freedriver")
    parser.add_argument("--config-key", default="route")
    parser.add_argument("--count-usernames", action="store_true")
    parser.add_argument("--apply-username-rule", action="store_true")
    parser.add_argument("--username-reject-pattern", action="store_true")
    parser.add_argument("--select-config-key", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return
    if args.username_reject_pattern:
        print(username_reject_pattern())
        return
    if args.path is None and not args.self_test:
        fail("expected a JSON file")
    data = json.loads(Path(args.path).read_text(encoding="utf-8"))
    if args.count_usernames:
        print(count_phone_usernames(data))
        return
    if args.apply_username_rule:
        if not isinstance(data, dict):
            fail("expected a user profile document")
        if apply_username_rule(data):
            Path(args.path).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
            raise SystemExit(0)
        raise SystemExit(3)
    if args.select_config_key:
        if not isinstance(data, dict):
            fail("expected an authenticator config description")
        print(select_config_key(data))
        return
    if isinstance(data, dict) and "executions" in data:
        data = data["executions"]
    if not isinstance(data, list):
        fail("expected a list of authentication executions")
    if args.plan:
        print(json.dumps(plan_next(data, args.flow, args.config_key)))
        return
    if args.browser_source:
        validate_browser_source(data)
        return
    validate(data, args.config_key)


if __name__ == "__main__":
    main()
