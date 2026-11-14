#!/usr/bin/env bash
# Asserts the three user api topic lines in mosquitto/acl.template.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="${ROOT}/mosquitto/acl.template"

python3 - "$TEMPLATE" <<'PY'
import re
import sys
from pathlib import Path

template = Path(sys.argv[1]).read_text()
blocks = re.split(r"(?m)^user ", template)
api = None
for block in blocks[1:]:
    name, _, rest = block.partition("\n")
    if name.strip() == "api":
        api = rest
        break
if api is None:
    raise SystemExit("template missing user api")
if "__INSTANCE_ID__" in api:
    raise SystemExit("user api grants are + filters")
topics = [line.strip() for line in api.splitlines() if line.startswith("topic ")]
need = {
    "topic read freedriver/v1/+/appliances",
    "topic write freedriver/v1/+/commands",
    "topic read freedriver/v1/+/metrics/#",
}
if not need.issubset(set(topics)):
    raise SystemExit(f"user api grants missing {need - set(topics)}")
unlisted = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
if unlisted in template:
    raise SystemExit("fixture house id aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa is reserved for this check")
print(f"api covers unlisted house {unlisted} via + grants")
PY
