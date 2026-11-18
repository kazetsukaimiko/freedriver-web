#!/usr/bin/env python3
"""Every file a test reads outside its module is in that module's build-cache key (#582).

The Maven build cache restores a module when its key is unchanged. A test that reads a file
outside its module (for example `Path.of("../../Caddyfile")`) only reruns when that file is
part of the module's key, so each such file is listed as a per-module input in the module's
pom: a property whose name starts with `maven.build.cache.input` (the `.glob` property aside),
with a path relative to the module directory. A directory input covers every file under it.

This script scans each Java module's src/test for string literals that start with `../`,
resolves each against the module directory, and fails naming the file when no input of that
module covers it. A path built in code from such a root (`root.resolve("x")` after
`Path.of("../..")`) is followed when the root and the `resolve` argument are string literals
in the same file. It sees only the reads it can find in the source text.

Usage:
  python3 .github/scripts/cache_inputs_check.py              check the repository (exit 1 on a gap)
  python3 .github/scripts/cache_inputs_check.py --self-test  check the rules on fixtures
"""
from __future__ import annotations

import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

INPUT_PREFIX = "maven.build.cache.input"
OUTSIDE_LITERAL = re.compile(r'"(\.\./[^"\s]*|\.\.)"')
ROOT_VARIABLE = re.compile(r'Path\s+(\w+)\s*=\s*Path\.of\("(\.\.(?:/\.\.)*)"\)')
RESOLVE = r'\b{var}\.resolve\("([^"]+)"\)'


def module_inputs(pom: Path) -> list[Path]:
    """Absolute paths of the module's per-module build-cache inputs."""
    root = ET.parse(pom).getroot()
    ns = {"m": root.tag.split("}")[0].strip("{")} if root.tag.startswith("{") else {}
    props = root.find("m:properties", ns) if ns else root.find("properties")
    if props is None:
        return []
    out = []
    for prop in props:
        name = prop.tag.split("}")[-1]
        if name.startswith(INPUT_PREFIX) and name != INPUT_PREFIX + ".glob" and prop.text:
            out.append(Path(os.path.normpath(pom.parent / prop.text.strip())))
    return out


def outside_reads(module: Path) -> dict[Path, list[str]]:
    """Files a module's tests read outside the module: resolved path -> where it was seen."""
    reads: dict[Path, list[str]] = {}
    tests = module / "src" / "test"
    if not tests.is_dir():
        return reads
    for source in sorted(tests.rglob("*.java")):
        text = source.read_text()
        rel = source.relative_to(module)
        roots = {var: path for var, path in ROOT_VARIABLE.findall(text)}
        for number, line in enumerate(text.splitlines(), 1):
            for literal in OUTSIDE_LITERAL.findall(line):
                if literal in roots.values():
                    continue
                target = Path(os.path.normpath(module / literal))
                reads.setdefault(target, []).append(f"{rel}:{number}")
            for var, base in roots.items():
                for child in re.findall(RESOLVE.format(var=re.escape(var)), line):
                    target = Path(os.path.normpath(module / base / child))
                    reads.setdefault(target, []).append(f"{rel}:{number}")
    return {path: where for path, where in reads.items() if not _inside(path, module)}


def _inside(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
        return True
    except ValueError:
        return False


def gaps(repo: Path) -> list[str]:
    """One line per outside read that no per-module input covers."""
    found = []
    for pom in sorted(repo.glob("java/*/pom.xml")):
        module = pom.parent
        inputs = module_inputs(pom)
        for path, where in sorted(outside_reads(module).items()):
            if not any(path == i or _inside(path, i) for i in inputs):
                shown = os.path.relpath(path, module)
                found.append(f"{module.relative_to(repo)}: a test reads {shown} ({', '.join(where)}); "
                             f"add <{INPUT_PREFIX}.outsideN>{shown}</{INPUT_PREFIX}.outsideN> "
                             f"to {pom.relative_to(repo)}")
    return found


def self_test() -> int:
    with tempfile.TemporaryDirectory() as d:
        repo = Path(d)
        (repo / "Caddyfile").write_text("x")
        (repo / "other" / "src" / "main").mkdir(parents=True)
        module = repo / "java" / "svc"
        tests = module / "src" / "test" / "java"
        tests.mkdir(parents=True)

        def pom(*inputs):
            props = "".join(f"<{INPUT_PREFIX}.outside{i}>{p}</{INPUT_PREFIX}.outside{i}>"
                            for i, p in enumerate(inputs, 1))
            (module / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<properties>{props}<{INPUT_PREFIX}.glob>*</{INPUT_PREFIX}.glob></properties></project>")

        (tests / "ATest.java").write_text(
            'class ATest {\n'
            '  String c = read(Path.of("../../Caddyfile"));\n'
            '  String in = read(Path.of("src/main/resources/x"));\n'
            '  Path root = Path.of("../..");\n'
            '  String d = read(root.resolve("other/src/main/Docker"));\n'
            '}\n')
        checks = []
        pom()
        lines = gaps(repo)
        checks.append(("both outside reads are gaps", len(lines) == 2
                       and any("../../Caddyfile" in l and "ATest.java:2" in l for l in lines)
                       and any("../../other/src/main/Docker" in l and "ATest.java:5" in l for l in lines)))
        pom("../../Caddyfile")
        checks.append(("a file input covers its read", len(gaps(repo)) == 1))
        pom("../../Caddyfile", "../../other/src/main")
        checks.append(("a directory input covers a file under it", gaps(repo) == []))
        pom("../../Caddyfile", "../../other/src/main/Dock")
        checks.append(("a prefix is not a directory", len(gaps(repo)) == 1))
        checks.append(("reads inside the module are ignored",
                       all("src/main/resources/x" not in l for l in gaps(repo))))
        (tests / "BTest.java").write_text('class BTest { String s = "../not-a-read-target"; }\n')
        checks.append(("a new outside literal is a gap naming the file",
                       any("../not-a-read-target" in l for l in gaps(repo))))
    failed = [name for name, ok in checks if not ok]
    for name in failed:
        print(f"FAIL: {name}")
    print(f"cache_inputs_check self-test: {len(checks) - len(failed)}/{len(checks)} passed")
    return 1 if failed else 0


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        return self_test()
    repo = Path(__file__).resolve().parents[2]
    lines = gaps(repo)
    for line in lines:
        print(f"::error::{line}")
    if lines:
        print(f"{len(lines)} test read(s) outside a module are missing from that module's build-cache key")
        return 1
    print("every test read outside a module is in that module's build-cache key")
    return 0


if __name__ == "__main__":
    sys.exit(main())
