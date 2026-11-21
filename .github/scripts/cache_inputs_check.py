#!/usr/bin/env python3
"""Every file a test reads outside its module is in that module's build-cache key (#582).

The Maven build cache restores a module when its key is unchanged. A test that reads a file
outside its module (for example `Path.of("../../Caddyfile")`) only reruns when that file is
part of the module's key, so each such file is listed as a per-module input in the module's
pom: a property whose name starts with `maven.build.cache.input` (the `.glob` property aside),
with a path relative to the module directory. A directory input covers every file under it.

This script scans the src/test of each module in the reactor (the `<module>` entries of the root
pom, followed into each aggregator pom) and of the standalone Keycloak provider
(`keycloak/sms-otp-spi`, a pom beside the reactor) for string literals that start with `../`,
resolves each against the module directory, and fails naming the file when no input of that
module covers it. A bare `".."` names a directory, not a file, and is not a read by itself:
sms-client's tests use `here.resolve("..").resolve(module)` to reach the compiled classes of
sms-model and sms-api, which are its reactor dependencies and so already in its key. A path built in code from such a root (`root.resolve("x")` after
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
OUTSIDE_LITERAL = re.compile(r'"(\.\./[^"\s]*)"')
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


def reactor_poms(repo: Path) -> list[Path]:
    """Every pom in the reactor: the root pom and the modules it lists, followed recursively."""
    found, todo = [], [repo / "pom.xml"]
    while todo:
        pom = todo.pop()
        if pom in found or not pom.is_file():
            continue
        found.append(pom)
        for modules in ET.parse(pom).getroot().iter():
            if modules.tag.split("}")[-1] != "modules":
                continue
            for module in modules:
                if module.tag.split("}")[-1] == "module" and module.text:
                    path = Path(os.path.normpath(pom.parent / module.text.strip()))
                    todo.append(path if path.name == "pom.xml" else path / "pom.xml")
    return sorted(found)


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


def scanned_poms(repo: Path) -> list[Path]:
    """Reactor poms plus the standalone provider pom."""
    found = reactor_poms(repo)
    standalone = repo / "keycloak" / "sms-otp-spi" / "pom.xml"
    if standalone.is_file() and standalone not in found:
        found.append(standalone)
    return sorted(found)


def gaps(repo: Path) -> list[str]:
    """One line per outside read that no per-module input covers."""
    found = []
    for pom in scanned_poms(repo):
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
        ns = 'xmlns="http://maven.apache.org/POM/4.0.0"'
        (repo / "pom.xml").write_text(f"<project {ns}><modules><module>java</module>"
                                      "<module>sms</module></modules></project>")
        (repo / "java").mkdir()
        (repo / "java" / "pom.xml").write_text(f"<project {ns}><modules><module>svc</module></modules></project>")
        (repo / "sms" / "src" / "test" / "java").mkdir(parents=True)
        (repo / "sms" / "pom.xml").write_text(f"<project {ns}></project>")
        (repo / "java" / "unlisted" / "src" / "test" / "java").mkdir(parents=True)
        (repo / "java" / "unlisted" / "pom.xml").write_text(f"<project {ns}></project>")
        (repo / "java" / "unlisted" / "src" / "test" / "java" / "UTest.java").write_text(
            'class UTest { String s = "../../Caddyfile"; }\n')
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
        (tests / "BTest.java").write_text(
            'class BTest { Path c = here.resolve("..").resolve(m).resolve("target/classes"); }\n')
        pom("../../Caddyfile", "../../other/src/main")
        checks.append(("a bare .. is not a read", gaps(repo) == []))
        (tests / "BTest.java").unlink()
        checks.append(("a module that is not in the reactor is not scanned", gaps(repo) == []))
        (repo / "sms" / "src" / "test" / "java" / "STest.java").write_text(
            'class STest { String c = read(Path.of("../docker-compose.yml")); }\n')
        lines = gaps(repo)
        checks.append(("a module outside java/ is scanned", len(lines) == 1
                       and lines[0].startswith("sms: a test reads ../docker-compose.yml")
                       and "to sms/pom.xml" in lines[0]))
        spi = repo / "keycloak" / "sms-otp-spi"
        (spi / "src" / "test" / "java").mkdir(parents=True)
        (spi / "pom.xml").write_text(f"<project {ns}></project>")
        (spi / "src" / "test" / "java" / "SpiTest.java").write_text(
            'class SpiTest { String s = Path.of("../../scripts/provision-keycloak-freedriver.sh"); }\n')
        lines = gaps(repo)
        checks.append(("a standalone provider module with a missing input is a gap",
                       any(line.startswith("keycloak/sms-otp-spi:")
                           and "../../scripts/provision-keycloak-freedriver.sh" in line
                           for line in lines)))
        (spi / "pom.xml").write_text(
            f"<project {ns}><properties>"
            f"<{INPUT_PREFIX}.outside1>../../scripts/provision-keycloak-freedriver.sh</{INPUT_PREFIX}.outside1>"
            f"</properties></project>")
        checks.append(("a standalone provider input covers its read",
                       all(not line.startswith("keycloak/sms-otp-spi:") for line in gaps(repo))))
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
