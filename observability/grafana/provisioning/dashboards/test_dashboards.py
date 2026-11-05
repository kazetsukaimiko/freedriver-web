#!/usr/bin/env python3
"""File-provisioned Grafana dashboards stay loadable and use stable uids."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
JSON_DIR = ROOT / "json"
PROVIDER = ROOT / "provider.yml"

EXPECTED = {
    "metrics.json": {
        "uid": "freedriver-metrics",
        "title": "Freedriver metrics",
        "datasource": "prometheus",
    },
    "logs.json": {
        "uid": "freedriver-logs",
        "title": "Freedriver logs",
        "datasource": "loki",
    },
}


def fail(msg: str) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(1)


def walk(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for item in node:
            yield from walk(item)


def main() -> None:
    text = PROVIDER.read_text(encoding="utf-8")
    if "path: /etc/grafana/provisioning/dashboards/json" not in text:
        fail("provider.yml must point Grafana at /etc/grafana/provisioning/dashboards/json")
    if "folder: Freedriver" not in text:
        fail("provider.yml must load dashboards into folder Freedriver")

    for name, expect in EXPECTED.items():
        path = JSON_DIR / name
        dash = json.loads(path.read_text(encoding="utf-8"))
        if dash.get("uid") != expect["uid"]:
            fail(f"{name} uid is {dash.get('uid')!r}, expected {expect['uid']!r}")
        if dash.get("title") != expect["title"]:
            fail(f"{name} title is {dash.get('title')!r}, expected {expect['title']!r}")
        if not isinstance(dash.get("schemaVersion"), int):
            fail(f"{name} needs a numeric schemaVersion")
        panels = dash.get("panels") or []
        if len(panels) < 2:
            fail(f"{name} needs at least two panels")
        for panel in panels:
            if not panel.get("title"):
                fail(f"{name} has a panel without a title")
            if not panel.get("gridPos"):
                fail(f"{name} panel {panel.get('title')!r} needs gridPos")
            ds = panel.get("datasource") or {}
            if ds.get("uid") not in {"prometheus", "loki"}:
                fail(f"{name} panel {panel.get('title')!r} datasource uid is {ds.get('uid')!r}")
            for target in panel.get("targets") or []:
                if not target.get("expr"):
                    fail(f"{name} panel {panel.get('title')!r} has a target without expr")
                legend = str(target.get("legendFormat") or "")
                if "instanceid" in legend.lower():
                    fail(f"{name} legendFormat uses instanceid: {legend!r}")
        blob = path.read_text(encoding="utf-8").lower()
        if "instanceid" in blob:
            fail(f"{name} mentions instanceid")
        uids = {
            node.get("uid")
            for node in walk(dash)
            if isinstance(node, dict) and node.get("type") in {"prometheus", "loki"}
        }
        if expect["datasource"] not in uids:
            fail(f"{name} must query datasource uid {expect['datasource']}")

    extras = sorted(p.name for p in JSON_DIR.glob("*.json") if p.name not in EXPECTED)
    if extras:
        fail(f"unexpected dashboard JSON: {extras}")
    print("grafana dashboards ok")


if __name__ == "__main__":
    main()
