#!/usr/bin/env python3
"""freedriver-api's home address is set when the client is created and when it is updated."""

import re
import sys
from pathlib import Path

SCRIPT = Path("scripts/provision-keycloak-freedriver.sh")
HOME = "https://app.freedriver.io/"
BASE = '-s "baseUrl=${HOME_URL}"'


def problems(text: str) -> list[str]:
    found: list[str] = []
    home = re.search(r"(?m)^HOME_URL='([^']+)'$", text)
    if home is None:
        found.append("HOME_URL in the provision script")
    elif home.group(1) != HOME:
        found.append(f"HOME_URL is {HOME}")
    uses = len(re.findall("baseUrl=", text))
    if uses != 2:
        found.append(
            "baseUrl is set twice: when freedriver-api is created and when it is updated"
            f" (found {uses})"
        )
    api = text.find("-s clientId=freedriver-api")
    update = text.find('kcadm update "clients/${CLIENT_UUID}"')
    nxt = text.find("default-client-scopes", update) if update >= 0 else -1
    if not (api > 0 and update > api and nxt > update):
        found.append("freedriver-api's create and update blocks")
        return found
    if BASE not in text[api:update]:
        found.append("set on create")
    if BASE not in text[update:nxt]:
        found.append("set on update")
    return found


def without_update_base_url(text: str) -> str:
    update = text.find('kcadm update "clients/${CLIENT_UUID}"')
    nxt = text.find("default-client-scopes", update)
    if update < 0 or nxt < 0:
        raise SystemExit("self-test could not find the client update block")
    block = text[update:nxt]
    lines = block.splitlines(keepends=True)
    kept: list[str] = []
    removed = False
    for line in lines:
        if not removed and "baseUrl=${HOME_URL}" in line:
            removed = True
            continue
        kept.append(line)
    if not removed:
        raise SystemExit("self-test could not find the update baseUrl")
    return text[:update] + "".join(kept) + text[nxt:]


def main() -> int:
    if not SCRIPT.is_file():
        raise SystemExit(f"missing {SCRIPT}")
    text = SCRIPT.read_text(encoding="utf-8")
    if "--self-test" in sys.argv:
        own = problems(text)
        if own:
            raise SystemExit("provision script failed its own check: " + "; ".join(own))
        broken = problems(without_update_base_url(text))
        if not broken:
            raise SystemExit("self-test: a copy without the update baseUrl still passed")
        print("check-provision-home-url self-test ok:", "; ".join(broken))
        return 0
    found = problems(text)
    if found:
        raise SystemExit("provision home address: " + "; ".join(found))
    print("freedriver-api home address is set on create and on update")
    return 0


if __name__ == "__main__":
    sys.exit(main())
