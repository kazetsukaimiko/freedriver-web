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


IO_PANEL_ID = 11
IO_PANEL_TARGETS = {
    "A": 'sum by (__name__) (rate({__name__=~"freedriver_io_.*_total"}[5m]))',
    "B": 'sum by (__name__) ({__name__=~"freedriver_io_queue.*"})',
}
TIMER_CALLS = "sum by (class, method) (rate(freedriver_io_blocking_seconds_count[5m]))"
TIMER_MEAN = (
    "sum by (class, method) (rate(freedriver_io_blocking_seconds_sum[5m]))"
    " / sum by (class, method) (rate(freedriver_io_blocking_seconds_count[5m]))"
)
TIMER_UNITS = {TIMER_CALLS: "ops", TIMER_MEAN: "s"}
TIMER_LEGEND = "{{class}} {{method}}"
RETIRED_SERIES = "freedriver_io_blocking_ns_total"


def field_unit(panel: dict, ref_id: str):
    """The unit Grafana draws a query with: the panel default, replaced by a refId override."""
    config = panel.get("fieldConfig") or {}
    unit = (config.get("defaults") or {}).get("unit")
    for override in config.get("overrides") or []:
        matcher = override.get("matcher") or {}
        if matcher.get("id") != "byFrameRefID" or matcher.get("options") != ref_id:
            continue
        for prop in override.get("properties") or []:
            if prop.get("id") == "unit":
                unit = prop.get("value")
    return unit


def overlaps(a: dict, b: dict) -> bool:
    return (
        a["x"] < b["x"] + b["w"]
        and b["x"] < a["x"] + a["w"]
        and a["y"] < b["y"] + b["h"]
        and b["y"] < a["y"] + a["h"]
    )


def check_unique_panel_ids(name: str, panels: list) -> None:
    seen = set()
    for panel in panels:
        panel_id = panel.get("id")
        if panel_id in seen:
            fail(f"{name} has two panels with id {panel_id!r}")
        seen.add(panel_id)


def check_blocking_io_timer(panels: list) -> None:
    """Panel 11 keeps its two queries, and one other panel holds the blocking-IO timer."""
    for panel in panels:
        for target in panel.get("targets") or []:
            if RETIRED_SERIES in (target.get("expr") or ""):
                fail(f"metrics.json panel {panel.get('title')!r} names {RETIRED_SERIES}")

    io_panels = [p for p in panels if p.get("id") == IO_PANEL_ID]
    if len(io_panels) != 1:
        fail(f"metrics.json needs one panel with id {IO_PANEL_ID}")
    io_targets = [(t.get("refId"), t.get("expr")) for t in io_panels[0].get("targets") or []]
    if io_targets != list(IO_PANEL_TARGETS.items()):
        fail(f"metrics.json panel {IO_PANEL_ID} targets are {io_targets!r}")

    holders = {
        expr: [p for p in panels if any(t.get("expr") == expr for t in p.get("targets") or [])]
        for expr in TIMER_UNITS
    }
    for expr, found in holders.items():
        if len(found) != 1:
            fail(f"metrics.json needs one panel with the query {expr!r}, found {len(found)}")
    panel = holders[TIMER_CALLS][0]
    if holders[TIMER_MEAN][0] is not panel:
        fail("metrics.json must hold both blocking-IO timer queries on one panel")
    panel_id = panel.get("id")
    if not isinstance(panel_id, int) or 1 <= panel_id <= IO_PANEL_ID:
        fail(f"metrics.json timer panel id is {panel_id!r}, expected a new id outside 1 to {IO_PANEL_ID}")
    if panel.get("type") != "timeseries":
        fail(f"metrics.json timer panel type is {panel.get('type')!r}, expected 'timeseries'")
    targets = panel.get("targets") or []
    if sorted(t.get("expr") or "" for t in targets) != sorted(TIMER_UNITS):
        fail("metrics.json timer panel must hold the calls query and the mean query, once each")
    ref_ids = [t.get("refId") for t in targets]
    if len(set(ref_ids)) != len(ref_ids):
        fail(f"metrics.json timer panel refIds are {ref_ids!r}")
    for target in targets:
        expr = target["expr"]
        unit = field_unit(panel, target.get("refId"))
        if unit != TIMER_UNITS[expr]:
            fail(f"metrics.json timer query {expr!r} has unit {unit!r}, expected {TIMER_UNITS[expr]!r}")
        if target.get("legendFormat") != TIMER_LEGEND:
            fail(f"metrics.json timer query {expr!r} legendFormat is {target.get('legendFormat')!r}")
    for other in panels:
        if other is not panel and overlaps(panel["gridPos"], other["gridPos"]):
            fail(f"metrics.json timer panel overlaps panel {other.get('title')!r}")


def main() -> None:
    text = PROVIDER.read_text(encoding="utf-8")
    if "path: /etc/grafana/provisioning/dashboards/json" not in text:
        fail("provider.yml must point Grafana at /etc/grafana/provisioning/dashboards/json")
    if "folder: Freedriver" not in text:
        fail("provider.yml must load dashboards into folder Freedriver")
    if "disableDeletion: true" not in text:
        fail("provider.yml must set disableDeletion: true")
    if "allowUiUpdates: false" not in text:
        fail("provider.yml must set allowUiUpdates: false")

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
        check_unique_panel_ids(name, panels)
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

    metrics = json.loads((JSON_DIR / "metrics.json").read_text(encoding="utf-8"))
    check_blocking_io_timer(metrics.get("panels") or [])

    logs = json.loads((JSON_DIR / "logs.json").read_text(encoding="utf-8"))
    log_exprs = [
        target.get("expr") or ""
        for panel in logs.get("panels") or []
        for target in panel.get("targets") or []
    ]
    if not log_exprs:
        fail("logs.json needs query exprs")
    if any("Connection not authenticating" not in expr for expr in log_exprs):
        fail("logs.json must filter MongoDB healthcheck Connection not authenticating")
    if not any("| json" in expr and "line_format" in expr for expr in log_exprs):
        fail("logs.json must parse JSON lines with json | line_format")
    logs_panels = [p for p in logs.get("panels") or [] if p.get("type") == "logs"]
    if len(logs_panels) < 2:
        fail("logs.json needs logs panels for lines and errors")
    for panel in logs_panels:
        opts = panel.get("options") or {}
        if opts.get("prettifyLogMessage") is not True:
            fail(f"logs.json panel {panel.get('title')!r} must prettify JSON")
        if len(panel.get("targets") or []) < 2:
            fail(f"logs.json panel {panel.get('title')!r} needs plain and JSON queries")

    extras = sorted(p.name for p in JSON_DIR.glob("*.json") if p.name not in EXPECTED)
    if extras:
        fail(f"unexpected dashboard JSON: {extras}")
    print("grafana dashboards ok")


if __name__ == "__main__":
    main()
