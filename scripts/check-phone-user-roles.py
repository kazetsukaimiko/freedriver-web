#!/usr/bin/env python3
"""Exit 0 when effective realm roles stay inside the phone-user allow-list.

A client role mapping fails the check. Role names on stderr are not secrets.
"""

import json
import sys


def main() -> int:
    composite_path, mappings_path, realm = sys.argv[1:4]
    allowed = {
        "dashboard",
        f"default-roles-{realm}",
        "offline_access",
        "uma_authorization",
        "grafana-viewer",
        "grafana-editor",
        "grafana-admin",
    }
    composite = json.load(open(composite_path, encoding="utf-8"))
    mappings = json.load(open(mappings_path, encoding="utf-8"))
    if not isinstance(composite, list):
        print("realm composite role list is missing", file=sys.stderr)
        return 1
    names = {role.get("name") for role in composite}
    extra = sorted(name for name in names if name not in allowed)
    client = mappings.get("clientMappings") or {}
    if extra or client:
        if extra:
            print("realm roles outside the allow-list: " + ", ".join(extra), file=sys.stderr)
        if client:
            print("client roles are present", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
