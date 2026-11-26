#!/usr/bin/env python3
"""Read-only check that freedriver-api reaches an access token only from pinned registrations.

The snapshot is kcadm output in one object:

- clients: each client id, protocol, own protocol mappers, and default and
  optional client-scope names
- clientScopes: each scope name, protocol, and protocol mappers
- realmDefaultDefaultClientScopes and realmDefaultOptionalClientScopes
- anonymousAllowedProtocolMapperTypes: the anonymous allowed-protocol-mappers
  component's allowed-protocol-mapper-types, compared as a set
- freedriverApiClientRoles: the freedriver-api client's roles

A passing snapshot has the audience mapper for client audience freedriver-api
on freedriver-autonomy-mcp and freedriver-ios only, on those registrations'
own mappers. A scope a registration lists, and a realm default-default or
default-optional scope, is scanned the same way. An unrecognized openid-connect
mapper type, or a setting this script cannot judge, is a failure. The failure
names the client or scope and the mapper.

Exit 0 when the snapshot passes. Exit 1 prints each failure on stderr.
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path

DASHBOARD_AUDIENCE = "freedriver-api"
ALLOWED_AUDIENCE_CLIENTS = frozenset({"freedriver-autonomy-mcp", "freedriver-ios"})

ANONYMOUS_MAPPER_TYPES = frozenset(
    {
        "oidc-full-name-mapper",
        "oidc-sha256-pairwise-sub-mapper",
        "oidc-address-mapper",
        "saml-user-property-mapper",
        "saml-user-attribute-mapper",
        "saml-role-list-mapper",
    }
)

# Settings are fixed by the mapper type. None of these write aud.
FIXED_CLAIM_TYPES = frozenset(
    {
        "oidc-sub-mapper",
        "oidc-full-name-mapper",
        "oidc-address-mapper",
        "oidc-allowed-origins-mapper",
        "oidc-acr-mapper",
        "oidc-sha256-pairwise-sub-mapper",
    }
)

# claim.name chooses the claim. aud and aud.* are the dashboard audience.
CONFIGURABLE_CLAIM_TYPES = frozenset(
    {
        "oidc-usermodel-attribute-mapper",
        "oidc-usermodel-property-mapper",
        "oidc-usermodel-realm-role-mapper",
        "oidc-usermodel-client-role-mapper",
        "oidc-usersessionmodel-note-mapper",
        "oidc-hardcoded-claim-mapper",
        "oidc-organization-membership-mapper",
    }
)

OK_FIXTURE = Path(__file__).resolve().parent / "fixtures" / "app-audience-ok.json"


def config_string(config: object, key: str) -> tuple[str | None, str]:
    """Return the config string and absent, ok, or bad.

    Keycloak stores a protocol-mapper config value as a string. A one-element
    list is the same value from a component-config read. Any other shape is
    a setting this check cannot judge.
    """
    if not isinstance(config, dict) or key not in config:
        return None, "absent"
    raw = config[key]
    if isinstance(raw, str):
        return raw, "ok"
    if isinstance(raw, list) and len(raw) == 1 and isinstance(raw[0], str):
        return raw[0], "ok"
    return None, "bad"


def claim_writes_dashboard_audience(name: str) -> bool:
    return name == "aud" or name.startswith("aud.")


def locator(kind: str, owner: str, mapper: dict) -> str:
    mapper_name = mapper.get("name") if isinstance(mapper.get("name"), str) else "(unnamed)"
    mapper_type = mapper.get("protocolMapper") if isinstance(mapper.get("protocolMapper"), str) else "(missing type)"
    return f"{kind} {owner} mapper {mapper_name} ({mapper_type})"


def classify_mapper(kind: str, owner: str, mapper: object, api_roles_empty: bool) -> list[str]:
    if not isinstance(mapper, dict):
        return [f"{kind} {owner} mapper entry is unreadable"]
    place = locator(kind, owner, mapper)
    protocol = mapper.get("protocol", "openid-connect")
    if protocol != "openid-connect":
        return [f"{place}: protocol {protocol} on an openid-connect {kind}"]
    mapper_type = mapper.get("protocolMapper")
    if not isinstance(mapper_type, str) or not mapper_type:
        return [f"{place}: mapper type is missing"]
    if mapper_type.startswith("saml-"):
        return [f"{place}: saml mapper on an openid-connect {kind}"]
    config = mapper.get("config", {})
    if config is None:
        config = {}
    if not isinstance(config, dict):
        return [f"{place}: config is unreadable"]

    if mapper_type in FIXED_CLAIM_TYPES:
        return []

    if mapper_type in CONFIGURABLE_CLAIM_TYPES:
        claim, state = config_string(config, "claim.name")
        if state != "ok" or claim is None:
            return [f"{place}: claim.name cannot be judged"]
        if claim_writes_dashboard_audience(claim):
            return [f"{place}: claim.name {claim} writes aud"]
        return []

    if mapper_type == "oidc-audience-mapper":
        failures = []
        custom, custom_state = config_string(config, "included.custom.audience")
        client_aud, client_state = config_string(config, "included.client.audience")
        if custom_state == "bad":
            failures.append(f"{place}: included.custom.audience cannot be judged")
        elif custom == DASHBOARD_AUDIENCE:
            failures.append(f"{place}: included.custom.audience is {DASHBOARD_AUDIENCE}")
        if client_state == "bad":
            failures.append(f"{place}: included.client.audience cannot be judged")
        elif client_aud == DASHBOARD_AUDIENCE and kind == "scope":
            failures.append(f"{place}: scope carries client audience {DASHBOARD_AUDIENCE}")
        return failures

    if mapper_type == "oidc-hardcoded-role-mapper":
        role, state = config_string(config, "role")
        if state != "ok" or role is None:
            return [f"{place}: role cannot be judged"]
        if role == DASHBOARD_AUDIENCE or role.startswith(DASHBOARD_AUDIENCE + "."):
            return [f"{place}: role {role} names a {DASHBOARD_AUDIENCE} client role"]
        return []

    if mapper_type == "oidc-audience-resolve-mapper":
        if api_roles_empty:
            return []
        return [f"{place}: audience resolve can add {DASHBOARD_AUDIENCE} from a client role"]

    return [f"{place}: unrecognized mapper type {mapper_type}"]


def assigned_scope_names(snapshot: dict) -> list[str]:
    names: list[str] = []
    seen: set[str] = set()

    def add(raw: object, label: str) -> None:
        if raw is None:
            return
        if not isinstance(raw, list):
            names.append(f"\0bad:{label}")
            return
        for item in raw:
            if isinstance(item, str) and item and item not in seen:
                seen.add(item)
                names.append(item)
            elif not isinstance(item, str) or not item:
                names.append(f"\0bad:{label}")

    for client in snapshot.get("clients") or []:
        if not isinstance(client, dict):
            continue
        client_id = client.get("clientId") if isinstance(client.get("clientId"), str) else "(unnamed client)"
        add(client.get("defaultClientScopes"), f"client {client_id} default scope")
        add(client.get("optionalClientScopes"), f"client {client_id} optional scope")
    add(snapshot.get("realmDefaultDefaultClientScopes"), "realm default-default scope")
    add(snapshot.get("realmDefaultOptionalClientScopes"), "realm default-optional scope")
    return names


def api_role_names(snapshot: dict) -> tuple[list[str] | None, list[str]]:
    roles = snapshot.get("freedriverApiClientRoles")
    if not isinstance(roles, list):
        return None, ["freedriver-api client roles are missing"]
    names: list[str] = []
    for role in roles:
        if isinstance(role, str) and role:
            names.append(role)
            continue
        if isinstance(role, dict) and isinstance(role.get("name"), str) and role["name"]:
            names.append(role["name"])
            continue
        return None, ["freedriver-api client role entry is unreadable"]
    return names, []


def evaluate(snapshot: object) -> list[str]:
    if not isinstance(snapshot, dict):
        return ["snapshot is missing"]
    failures: list[str] = []
    for key in (
        "clients",
        "clientScopes",
        "realmDefaultDefaultClientScopes",
        "realmDefaultOptionalClientScopes",
        "anonymousAllowedProtocolMapperTypes",
        "freedriverApiClientRoles",
    ):
        if not isinstance(snapshot.get(key), list):
            failures.append(f"{key} is missing")
    if failures:
        return failures

    role_names, role_failures = api_role_names(snapshot)
    failures.extend(role_failures)
    api_roles_empty = role_names == []
    if role_names:
        failures.append("freedriver-api has client roles: " + ", ".join(sorted(role_names)))

    scopes_by_name: dict[str, dict] = {}
    for scope in snapshot["clientScopes"]:
        if not isinstance(scope, dict) or not isinstance(scope.get("name"), str) or not scope["name"]:
            failures.append("client scope entry is unreadable")
            continue
        name = scope["name"]
        if name in scopes_by_name:
            failures.append(f"client scope {name} is listed twice")
            continue
        scopes_by_name[name] = scope

    carriers: set[str] = set()
    seen_clients: set[str] = set()
    for client in snapshot["clients"]:
        if not isinstance(client, dict) or not isinstance(client.get("clientId"), str) or not client["clientId"]:
            failures.append("client entry is unreadable")
            continue
        client_id = client["clientId"]
        if client_id in seen_clients:
            failures.append(f"client {client_id} is listed twice")
            continue
        seen_clients.add(client_id)
        protocol = client.get("protocol")
        if protocol != "openid-connect":
            shown = protocol if isinstance(protocol, str) and protocol else "missing"
            failures.append(f"client {client_id}: protocol {shown}")
            continue
        mappers = client.get("protocolMappers", [])
        if not isinstance(mappers, list):
            failures.append(f"client {client_id}: protocol mappers are unreadable")
            continue
        for mapper in mappers:
            failures.extend(classify_mapper("client", client_id, mapper, api_roles_empty))
            if not isinstance(mapper, dict):
                continue
            if mapper.get("protocolMapper") != "oidc-audience-mapper":
                continue
            audience, state = config_string(mapper.get("config", {}), "included.client.audience")
            if state == "ok" and audience == DASHBOARD_AUDIENCE:
                carriers.add(client_id)

    scope_names = assigned_scope_names(snapshot)
    for name in scope_names:
        if name.startswith("\0bad:"):
            failures.append(name[5:] + " name is unreadable")
            continue
        scope = scopes_by_name.get(name)
        if scope is None:
            failures.append(f"client scope {name} is assigned and missing from clientScopes")
            continue
        protocol = scope.get("protocol")
        if protocol != "openid-connect":
            shown = protocol if isinstance(protocol, str) and protocol else "missing"
            failures.append(f"scope {name}: protocol {shown}")
            continue
        mappers = scope.get("protocolMappers", [])
        if not isinstance(mappers, list):
            failures.append(f"scope {name}: protocol mappers are unreadable")
            continue
        for mapper in mappers:
            failures.extend(classify_mapper("scope", name, mapper, api_roles_empty))

    if carriers != ALLOWED_AUDIENCE_CLIENTS:
        got = ", ".join(sorted(carriers)) if carriers else "(none)"
        expected = ", ".join(sorted(ALLOWED_AUDIENCE_CLIENTS))
        failures.append(f"freedriver-api audience registrations are {got}; expected {expected}")

    allowed = snapshot["anonymousAllowedProtocolMapperTypes"]
    if not all(isinstance(item, str) and item for item in allowed):
        failures.append("anonymous allowed-protocol-mapper-types entry is unreadable")
    elif set(allowed) != ANONYMOUS_MAPPER_TYPES:
        extra = sorted(set(allowed) - ANONYMOUS_MAPPER_TYPES)
        missing = sorted(ANONYMOUS_MAPPER_TYPES - set(allowed))
        detail = []
        if extra:
            detail.append("extra " + ", ".join(extra))
        if missing:
            detail.append("missing " + ", ".join(missing))
        failures.append("anonymous allowed-protocol-mapper-types " + "; ".join(detail))
    return failures


def load_ok() -> dict:
    return json.loads(OK_FIXTURE.read_text(encoding="utf-8"))


def expect_ok(snapshot: dict, label: str) -> None:
    failures = evaluate(snapshot)
    if failures:
        raise SystemExit(label + " should pass:\n" + "\n".join(failures))


def expect_failure(snapshot: dict, needle: str, label: str) -> None:
    failures = evaluate(snapshot)
    if not any(needle in failure for failure in failures):
        raise SystemExit(label + " should name " + needle + ":\n" + "\n".join(failures))


def client_named(snapshot: dict, client_id: str) -> dict:
    for client in snapshot["clients"]:
        if client.get("clientId") == client_id:
            return client
    raise SystemExit(f"fixture is missing client {client_id}")


def scope_named(snapshot: dict, name: str) -> dict:
    for scope in snapshot["clientScopes"]:
        if scope.get("name") == name:
            return scope
    raise SystemExit(f"fixture is missing scope {name}")


def self_test() -> None:
    ok = load_ok()
    expect_ok(ok, "fixture")

    session = copy.deepcopy(ok)
    client_named(session, "freedriver-dispatcher")["protocolMappers"].append(
        {
            "name": "session-note",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-usersessionmodel-note-mapper",
            "config": {"claim.name": "session_note"},
        }
    )
    expect_ok(session, "session note claim")

    extra = copy.deepcopy(ok)
    extra["clients"].append(
        {
            "clientId": "grok-example",
            "protocol": "openid-connect",
            "protocolMappers": [
                {
                    "name": "audience-freedriver-api",
                    "protocol": "openid-connect",
                    "protocolMapper": "oidc-audience-mapper",
                    "config": {"included.client.audience": "freedriver-api"},
                }
            ],
            "defaultClientScopes": ["basic"],
            "optionalClientScopes": [],
        }
    )
    expect_failure(extra, "grok-example", "extra registration")

    scope_aud = copy.deepcopy(ok)
    scope_named(scope_aud, "basic")["protocolMappers"].append(
        {
            "name": "audience-freedriver-api",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "config": {"included.client.audience": "freedriver-api"},
        }
    )
    expect_failure(scope_aud, "scope basic", "scope audience")

    claim = copy.deepcopy(ok)
    scope_named(claim, "profile")["protocolMappers"][1]["config"]["claim.name"] = "aud"
    expect_failure(claim, "claim.name aud writes aud", "claim aud")

    nested = copy.deepcopy(ok)
    scope_named(nested, "profile")["protocolMappers"][1]["config"]["claim.name"] = "aud.extra"
    expect_failure(nested, "aud.extra", "nested aud claim")

    unknown = copy.deepcopy(ok)
    client_named(unknown, "freedriver-api")["protocolMappers"].append(
        {
            "name": "groups",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-group-membership-mapper",
            "config": {"claim.name": "groups"},
        }
    )
    expect_failure(unknown, "unrecognized mapper type oidc-group-membership-mapper", "unknown type")

    saml = copy.deepcopy(ok)
    client_named(saml, "freedriver-api")["protocolMappers"].append(
        {
            "name": "saml-role",
            "protocol": "openid-connect",
            "protocolMapper": "saml-role-list-mapper",
            "config": {},
        }
    )
    expect_failure(saml, "saml mapper", "saml on oidc")

    roles = copy.deepcopy(ok)
    roles["freedriverApiClientRoles"] = [{"name": "manage"}]
    expect_failure(roles, "freedriver-api has client roles: manage", "api client role")
    expect_failure(roles, "audience resolve can add freedriver-api", "resolve with a client role")

    hardcoded = copy.deepcopy(ok)
    role_mapper = next(
        mapper
        for mapper in client_named(hardcoded, "freedriver-dispatcher")["protocolMappers"]
        if mapper["name"] == "role-dispatcher"
    )
    role_mapper["config"]["role"] = "freedriver-api.manage"
    expect_failure(hardcoded, "freedriver-api.manage", "hardcoded api role")

    policy = copy.deepcopy(ok)
    policy["anonymousAllowedProtocolMapperTypes"] = list(ANONYMOUS_MAPPER_TYPES) + [
        "oidc-usermodel-property-mapper"
    ]
    expect_failure(policy, "extra oidc-usermodel-property-mapper", "anonymous list")

    custom = copy.deepcopy(ok)
    scope_named(custom, "autonomy-mcp")["protocolMappers"][0]["config"]["included.custom.audience"] = (
        "freedriver-api"
    )
    expect_failure(custom, "included.custom.audience is freedriver-api", "custom audience")

    missing_ios = copy.deepcopy(ok)
    client_named(missing_ios, "freedriver-ios")["protocolMappers"] = []
    expect_failure(missing_ios, "expected freedriver-autonomy-mcp, freedriver-ios", "missing ios mapper")

    unjudgeable = copy.deepcopy(ok)
    client_named(unjudgeable, "freedriver-ios")["protocolMappers"][0]["config"]["included.client.audience"] = [
        "freedriver-api",
        "other",
    ]
    expect_failure(unjudgeable, "included.client.audience cannot be judged", "multi-value audience")

    print("app audience self-test ok")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.path:
        print("expected a snapshot JSON file", file=sys.stderr)
        return 1
    snapshot = json.loads(Path(args.path).read_text(encoding="utf-8"))
    failures = evaluate(snapshot)
    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
