#!/usr/bin/env python3
"""The Keycloak server image, the SMS OTP SPI, and the Keycloak test scripts share the root pom keycloak.version.

Reads that property from project/properties with an XML parser. Compares the
quay.io/keycloak/keycloak tag on the runtime FROM line, the SPI property, and
the shell assignments IMAGE and KC_VERSION. A missing, non-literal, or repeated
pin fails and names the file and the pin. Pass a repository root to check a
checkout other than the working directory; --self-test checks temporary copies.
"""

from __future__ import annotations

import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT_POM = "pom.xml"
DOCKERFILE = "keycloak/Dockerfile"
SPI_POM = "keycloak/sms-otp-spi/pom.xml"
EXCHANGE = "scripts/test-autonomy-token-exchange.sh"
INVITE = "scripts/test-invite-service-account.sh"

ROOT_PIN = "root pom.xml keycloak.version"
DOCKER_PIN = "keycloak/Dockerfile FROM quay.io/keycloak/keycloak"
SPI_PIN = "keycloak/sms-otp-spi/pom.xml keycloak.version"
EXCHANGE_IMAGE = "scripts/test-autonomy-token-exchange.sh IMAGE"
EXCHANGE_KC = "scripts/test-autonomy-token-exchange.sh KC_VERSION"
INVITE_IMAGE = "scripts/test-invite-service-account.sh IMAGE"

IMAGE = "quay.io/keycloak/keycloak"
FILES = (ROOT_POM, DOCKERFILE, SPI_POM, EXCHANGE, INVITE)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def version_property(path: Path, pin: str) -> tuple[str | None, list[str]]:
    """The single keycloak.version text in project/properties, or a pin failure."""
    if not path.is_file():
        return None, [f"{pin} is missing"]
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return None, [f"{pin} is not a literal"]
    props = [child for child in list(root) if local_name(child.tag) == "properties"]
    if len(props) != 1:
        return None, [f"{pin} is missing"]
    values = [child for child in list(props[0]) if local_name(child.tag) == "keycloak.version"]
    if len(values) == 0:
        return None, [f"{pin} is missing"]
    if len(values) > 1:
        return None, [f"{pin} is ambiguous"]
    text = (values[0].text or "").strip()
    if text == "":
        return None, [f"{pin} is missing"]
    if "$" in text or "`" in text:
        return None, [f"{pin} is not a literal"]
    return text, []


def from_pins(text: str) -> list[tuple[str, str]]:
    """Active FROM lines for quay.io/keycloak/keycloak. Other stages are left out."""
    found: list[tuple[str, str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = re.match(r"^FROM\s+(\S+)", stripped)
        if not match:
            continue
        token = match.group(1)
        if "@" in token:
            name, tag = token.split("@", 1)[0], None
        elif ":" in token:
            name, tag = token.rsplit(":", 1)
        else:
            name, tag = token, None
        if name != IMAGE:
            continue
        if tag is None or "$" in tag or "$" in token:
            found.append(("nonliteral", token))
        else:
            found.append(("literal", tag))
    return found


def dockerfile_tag(text: str, pin: str) -> tuple[str | None, list[str]]:
    pins = from_pins(text)
    if len(pins) == 0:
        return None, [f"{pin} is missing"]
    if len(pins) > 1:
        return None, [f"{pin} is ambiguous"]
    kind, detail = pins[0]
    if kind == "nonliteral":
        return None, [f"{pin} is not a literal"]
    return detail, []


def assignments(text: str, name: str) -> list[str]:
    pattern = re.compile(rf"^(?:export\s+)?{name}=(.*)$")
    found: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        match = pattern.match(stripped)
        if match:
            found.append(match.group(1).strip())
    return found


def unquote(raw: str) -> str | None:
    """A shell literal with the surrounding quotes removed. None when it expands."""
    if raw == "" or "$" in raw or "`" in raw:
        return None
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "\"'":
        raw = raw[1:-1]
        if raw == "" or "$" in raw or "`" in raw:
            return None
    if any(char.isspace() for char in raw):
        return None
    return raw


def image_tag(text: str, pin: str) -> tuple[str | None, list[str]]:
    raws = assignments(text, "IMAGE")
    if len(raws) == 0:
        return None, [f"{pin} is missing"]
    if len(raws) > 1:
        return None, [f"{pin} is ambiguous"]
    value = unquote(raws[0])
    if value is None:
        return None, [f"{pin} is not a literal"]
    match = re.fullmatch(rf"{re.escape(IMAGE)}:(\S+)", value)
    if not match:
        return None, [f"{pin} is {value}"]
    return match.group(1), []


def version_assignment(text: str, pin: str) -> tuple[str | None, list[str]]:
    raws = assignments(text, "KC_VERSION")
    if len(raws) == 0:
        return None, [f"{pin} is missing"]
    if len(raws) > 1:
        return None, [f"{pin} is ambiguous"]
    value = unquote(raws[0])
    if value is None:
        return None, [f"{pin} is not a literal"]
    return value, []


def read_text(path: Path, pin: str) -> tuple[str | None, list[str]]:
    if not path.is_file():
        return None, [f"{pin} is missing"]
    return path.read_text(encoding="utf-8"), []


def disagree(pin: str, actual: str | None, failures: list[str], expected: str | None) -> None:
    if actual is None or expected is None or actual == expected:
        return
    failures.append(f"{pin} is {actual}, root keycloak.version is {expected}")


def check(root: Path) -> list[str]:
    failures: list[str] = []
    expected, root_failures = version_property(root / ROOT_POM, ROOT_PIN)
    failures.extend(root_failures)

    dockerfile, docker_failures = read_text(root / DOCKERFILE, DOCKER_PIN)
    failures.extend(docker_failures)
    tag = None
    if dockerfile is not None:
        tag, tag_failures = dockerfile_tag(dockerfile, DOCKER_PIN)
        failures.extend(tag_failures)
    disagree(DOCKER_PIN, tag, failures, expected)

    spi, spi_failures = version_property(root / SPI_POM, SPI_PIN)
    failures.extend(spi_failures)
    disagree(SPI_PIN, spi, failures, expected)

    exchange_path = root / EXCHANGE
    exchange_image = None
    exchange_kc = None
    if not exchange_path.is_file():
        failures.append(f"{EXCHANGE_IMAGE} is missing")
        failures.append(f"{EXCHANGE_KC} is missing")
        exchange = None
    else:
        exchange = exchange_path.read_text(encoding="utf-8")
    if exchange is not None:
        exchange_image, image_failures = image_tag(exchange, EXCHANGE_IMAGE)
        failures.extend(image_failures)
        exchange_kc, kc_failures = version_assignment(exchange, EXCHANGE_KC)
        failures.extend(kc_failures)
    disagree(EXCHANGE_IMAGE, exchange_image, failures, expected)
    disagree(EXCHANGE_KC, exchange_kc, failures, expected)

    invite, invite_failures = read_text(root / INVITE, INVITE_IMAGE)
    failures.extend(invite_failures)
    invite_image = None
    if invite is not None:
        invite_image, invite_image_failures = image_tag(invite, INVITE_IMAGE)
        failures.extend(invite_image_failures)
    disagree(INVITE_IMAGE, invite_image, failures, expected)
    return failures


def snapshot(repo: Path, dest: Path) -> None:
    for relative in FILES:
        source = repo / relative
        if not source.is_file():
            raise SystemExit(f"self-test: missing {relative}")
        target = dest / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def replace_one(text: str, pattern: re.Pattern[str], repl: str, label: str) -> str:
    found = pattern.findall(text)
    if len(found) != 1:
        raise SystemExit(f"self-test: {label} matched {len(found)} times")
    return pattern.sub(repl, text, count=1)


def mutate(copy: Path, relative: str, pattern: re.Pattern[str], repl: str, label: str) -> None:
    path = copy / relative
    path.write_text(replace_one(path.read_text(encoding="utf-8"), pattern, repl, label), encoding="utf-8")


def require_failure(failures: list[str], pin: str, marker: str) -> None:
    if not failures:
        raise SystemExit(f"self-test: {pin} still passed")
    blob = "\n".join(failures)
    if pin not in blob:
        raise SystemExit(f"self-test: failure did not name {pin}: {blob}")
    if marker not in blob:
        raise SystemExit(f"self-test: {pin} failure lacked {marker!r}: {blob}")


def self_test(repo: Path) -> None:
    with tempfile.TemporaryDirectory() as directory:
        clean = Path(directory) / "clean"
        snapshot(repo, clean)
        clean_failures = check(clean)
        if clean_failures:
            raise SystemExit("self-test: clean copy failed: " + "; ".join(clean_failures))
        expected, _ = version_property(clean / ROOT_POM, ROOT_PIN)
        if not expected:
            raise SystemExit("self-test: clean copy has no root keycloak.version")
        mismatch = expected + ".9"

        def run(pin: str, marker: str, edit) -> None:
            with tempfile.TemporaryDirectory() as case_dir:
                copy = Path(case_dir)
                snapshot(repo, copy)
                edit(copy)
                require_failure(check(copy), pin, marker)

        run(
            DOCKER_PIN,
            mismatch,
            lambda copy: mutate(
                copy,
                DOCKERFILE,
                re.compile(rf"^(FROM\s+{re.escape(IMAGE)}:)(\S+)", re.M),
                rf"\g<1>{mismatch}",
                "runtime FROM",
            ),
        )
        run(
            SPI_PIN,
            mismatch,
            lambda copy: mutate(
                copy,
                SPI_POM,
                re.compile(r"<keycloak\.version>[^<]*</keycloak\.version>"),
                f"<keycloak.version>{mismatch}</keycloak.version>",
                "SPI keycloak.version",
            ),
        )
        image_pattern = re.compile(rf"^(IMAGE={re.escape(IMAGE)}:)(\S+)", re.M)
        run(
            EXCHANGE_IMAGE,
            mismatch,
            lambda copy: mutate(copy, EXCHANGE, image_pattern, rf"\g<1>{mismatch}", "token-exchange IMAGE"),
        )
        run(
            EXCHANGE_KC,
            mismatch,
            lambda copy: mutate(
                copy,
                EXCHANGE,
                re.compile(r"^(KC_VERSION=)(\S+)", re.M),
                rf"\g<1>{mismatch}",
                "KC_VERSION",
            ),
        )
        run(
            INVITE_IMAGE,
            mismatch,
            lambda copy: mutate(copy, INVITE, image_pattern, rf"\g<1>{mismatch}", "invite IMAGE"),
        )
        run(
            ROOT_PIN,
            "missing",
            lambda copy: mutate(
                copy,
                ROOT_POM,
                re.compile(r"\n[ \t]*<keycloak\.version>[^<]*</keycloak\.version>"),
                "",
                "root keycloak.version",
            ),
        )
        run(
            INVITE_IMAGE,
            "not a literal",
            lambda copy: mutate(
                copy,
                INVITE,
                re.compile(rf"^IMAGE=({re.escape(IMAGE)}:\S+)", re.M),
                r"IMAGE=${IMAGE:-\1}",
                "defaulted IMAGE",
            ),
        )
        run(
            DOCKER_PIN,
            "not a literal",
            lambda copy: mutate(
                copy,
                DOCKERFILE,
                re.compile(rf"^FROM {re.escape(IMAGE)}:\S+", re.M),
                "ARG KEYCLOAK_VERSION\nFROM " + IMAGE + ":${KEYCLOAK_VERSION}",
                "ARG-built FROM",
            ),
        )

        def duplicate_image(copy: Path) -> None:
            path = copy / INVITE
            text = path.read_text(encoding="utf-8")
            if len(image_pattern.findall(text)) != 1:
                raise SystemExit("self-test: invite IMAGE was not one assignment")
            path.write_text(text.rstrip() + f"\nIMAGE={IMAGE}:{mismatch}\n", encoding="utf-8")

        run(INVITE_IMAGE, "ambiguous", duplicate_image)
    print("check-keycloak-version self-test ok")


def main(argv: list[str]) -> int:
    self_test_mode = False
    positional: list[str] = []
    for arg in argv:
        if arg == "--self-test":
            self_test_mode = True
        elif arg.startswith("-"):
            raise SystemExit(f"unknown argument {arg}")
        else:
            positional.append(arg)
    if len(positional) > 1:
        raise SystemExit("pass one repository root")
    repo = Path(positional[0]) if positional else Path.cwd()
    if self_test_mode:
        self_test(repo)
        return 0
    failures = check(repo)
    if failures:
        raise SystemExit("keycloak version pins:\n" + "\n".join(failures))
    print("keycloak version pins match the root pom")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
