#!/usr/bin/env bash
# Match unlisted-house topics against user api filters with MQTT wildcards.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="${ROOT}/mosquitto/acl.template"

python3 - "$TEMPLATE" <<'PY'
import re
import sys
from pathlib import Path

template = Path(sys.argv[1]).read_text()
unlisted = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
other = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
if unlisted in template:
    raise SystemExit("fixture house id aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa is reserved for this check")


def mqtt_match(pattern: str, topic: str) -> bool:
    parts = pattern.split("/")
    levels = topic.split("/")
    i = 0
    j = 0
    while i < len(parts):
        if parts[i] == "#":
            return i == len(parts) - 1
        if j >= len(levels):
            return False
        if parts[i] != "+" and parts[i] != levels[j]:
            return False
        i += 1
        j += 1
    return j == len(levels)


def parse_users(text: str) -> dict[str, str]:
    users: dict[str, str] = {}
    for block in re.split(r"(?m)^user ", text)[1:]:
        name, _, rest = block.partition("\n")
        users[name.strip()] = rest
    return users


def parse_grants(block: str) -> list[tuple[str, str]]:
    grants: list[tuple[str, str]] = []
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped.startswith("topic "):
            continue
        parts = stripped.split()
        if len(parts) == 2:
            grants.append(("readwrite", parts[1]))
        elif len(parts) >= 3:
            grants.append((parts[1], parts[2]))
    return grants


def allowed(grants: list[tuple[str, str]], topic: str, action: str) -> bool:
    for mode, filt in grants:
        if not mqtt_match(filt, topic):
            continue
        if mode in (action, "readwrite"):
            return True
    return False


users = parse_users(template)
if "api" not in users:
    raise SystemExit("template missing user api")
if "__INSTANCE_ID__" in users["api"]:
    raise SystemExit("user api grants are + filters")
if "autonomy" not in users:
    raise SystemExit("template missing user autonomy")

api = parse_grants(users["api"])
appliances = f"freedriver/v1/{unlisted}/appliances"
commands = f"freedriver/v1/{unlisted}/commands"
command_lists = f"freedriver/v1/{unlisted}/command-lists"
scheduled = f"freedriver/v1/{unlisted}/scheduled"
if not allowed(api, appliances, "read"):
    raise SystemExit(f"api cannot read {appliances}")
if allowed(api, appliances, "write"):
    raise SystemExit(f"api can write {appliances}")
if not allowed(api, commands, "write"):
    raise SystemExit(f"api cannot write {commands}")
if allowed(api, commands, "read"):
    raise SystemExit(f"api can read {commands}")
if not allowed(api, command_lists, "write"):
    raise SystemExit(f"api cannot write {command_lists}")
if allowed(api, command_lists, "read"):
    raise SystemExit(f"api can read {command_lists}")
if not allowed(api, scheduled, "read"):
    raise SystemExit(f"api cannot read {scheduled}")
if allowed(api, scheduled, "write"):
    raise SystemExit(f"api can write {scheduled}")
print(f"api reads {appliances}")
print(f"api writes {commands}")
print(f"api writes {command_lists}")
print(f"api reads {scheduled}")

autonomy_block = users["autonomy"].replace("__INSTANCE_ID__", other)
autonomy = parse_grants(autonomy_block)
configured_lists = f"freedriver/v1/{other}/command-lists"
configured_scheduled = f"freedriver/v1/{other}/scheduled"
if not allowed(autonomy, configured_lists, "read"):
    raise SystemExit(f"autonomy cannot read {configured_lists}")
if allowed(autonomy, configured_lists, "write"):
    raise SystemExit(f"autonomy can write {configured_lists}")
if not allowed(autonomy, configured_scheduled, "write"):
    raise SystemExit(f"autonomy cannot write {configured_scheduled}")
if allowed(autonomy, configured_scheduled, "read"):
    raise SystemExit(f"autonomy can read {configured_scheduled}")
print(f"autonomy reads {configured_lists}")
print(f"autonomy writes {configured_scheduled}")
unlisted_topics = [
    appliances,
    commands,
    command_lists,
    scheduled,
    f"freedriver/v1/{unlisted}/metrics/x",
]
for topic in unlisted_topics:
    for mode, filt in autonomy:
        if mqtt_match(filt, topic):
            raise SystemExit(f"user autonomy {mode} {filt} matches {topic}")
print(f"autonomy grants miss unlisted house {unlisted}")
PY
