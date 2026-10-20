#!/usr/bin/env python3
"""Allowlist tests for the Alloy Docker list-and-logs proxy."""

from __future__ import annotations

import json
import os
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path

import docker_logs_proxy as proxy

REPO = Path(__file__).resolve().parents[2]
SECRET = "SUPER_SECRET_VALUE_121"
MOUNT = "/opt/freedriver-secrets/db-password"
COMMAND = "java -Dpassword=SECRET_ARG"


def _compose_services(text: str) -> dict[str, str]:
    blocks: dict[str, str] = {}
    current: str | None = None
    buf: list[str] = []
    in_services = False
    for line in text.splitlines():
        if not in_services:
            if line.startswith("services:"):
                in_services = True
            continue
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        if line.startswith("  ") and not line.startswith("   ") and line.endswith(":"):
            if current is not None:
                blocks[current] = "\n".join(buf)
            current = line.strip()[:-1]
            buf = [line]
            continue
        if current is not None:
            buf.append(line)
    if current is not None:
        blocks[current] = "\n".join(buf)
    return blocks


class ClassifyTest(unittest.TestCase):
    def test_allowlist(self) -> None:
        cases = {
            ("GET", "/_ping"): "ping",
            ("HEAD", "/_ping"): "ping",
            ("GET", "/v1.44/_ping"): "ping",
            ("HEAD", "/v1.44/_ping"): "ping",
            ("GET", "/v1.44/containers/json"): "list",
            ("GET", "/containers/json"): "list",
            ("GET", "/v1.44/containers/abc/json"): "inspect",
            ("GET", "/v1.44/containers/" + ("a" * 64) + "/json"): "inspect",
            ("GET", "/v1.44/containers/abc/logs"): "logs",
            ("GET", "/v1.44/networks"): "networks",
        }
        for (method, path), kind in cases.items():
            self.assertEqual(proxy.classify(method, path), kind, path)

    def test_denylist(self) -> None:
        denied = [
            ("POST", "/_ping"),
            ("GET", "/v1.44/containers/json/"),
            ("POST", "/v1.44/containers/json"),
            ("GET", "/v1.44/containers/create"),
            ("POST", "/v1.44/containers/create"),
            ("GET", "/v1.44/containers/abc/exec"),
            ("POST", "/v1.44/containers/abc/exec"),
            ("GET", "/v1.44/containers/abc/attach"),
            ("GET", "/v1.44/containers/abc/top"),
            ("GET", "/v1.44/containers/abc/archive"),
            ("GET", "/v1.44/containers/abc/stats"),
            ("DELETE", "/v1.44/containers/abc"),
            ("GET", "/v1.44/containers/abc/start"),
            ("GET", "/v1.44/networks/abc"),
            ("POST", "/v1.44/networks/abc/connect"),
            ("GET", "/v1.44/images/json"),
            ("GET", "/v1.44/info"),
            ("GET", "/v1.44/version"),
            ("GET", "/version"),
            ("GET", "/v1.44/events"),
            ("GET", "/v1.44/volumes"),
            ("GET", "/v1.44/containers/abc/logs/../../images/json"),
            ("GET", "/v1.44/containers/%2e%2e/json"),
            ("GET", "/v1.44/containers/ab%2fcd/json"),
            ("PUT", "/v1.44/containers/abc/json"),
            ("HEAD", "/v1.44/containers/json"),
            ("HEAD", "/v1.44/containers/abc/logs"),
        ]
        for method, path in denied:
            self.assertIsNone(proxy.classify(method, path), f"{method} {path}")


class RedactTest(unittest.TestCase):
    def test_inspect_keeps_tail_fields_only(self) -> None:
        raw = {
            "Id": "abc",
            "Name": "/dashboard",
            "Path": "/usr/bin/java",
            "Args": ["-Dpassword=SECRET_ARG"],
            "State": {
                "Status": "running",
                "Running": True,
                "Pid": 4242,
                "ExitCode": 0,
                "Error": "failed SECRET_ARG",
                "StartedAt": "2026-10-20T00:00:00.0Z",
                "FinishedAt": "0001-01-01T00:00:00Z",
            },
            "Config": {
                "Tty": False,
                "Env": [f"DB_PASSWORD={SECRET}", "PATH=/usr/bin"],
                "Cmd": ["java", "-Dpassword=SECRET_ARG"],
                "Labels": {"com.docker.compose.service": "dashboard"},
            },
            "HostConfig": {"Privileged": True, "Binds": [f"{MOUNT}:/run/secrets:ro"]},
            "Mounts": [{"Source": MOUNT, "Destination": "/run/secrets"}],
            "NetworkSettings": {"IPAddress": "172.18.0.2"},
        }
        cleaned = proxy.redact_inspect(raw)
        encoded = json.dumps(cleaned)
        self.assertNotIn(SECRET, encoded)
        self.assertNotIn(MOUNT, encoded)
        self.assertNotIn("SECRET_ARG", encoded)
        self.assertNotIn("Privileged", encoded)
        self.assertNotIn("Env", encoded)
        self.assertTrue(cleaned["State"]["Running"])
        self.assertEqual(cleaned["State"]["FinishedAt"], "0001-01-01T00:00:00Z")
        self.assertFalse(cleaned["Config"]["Tty"])
        self.assertNotIn("Pid", cleaned["State"])

    def test_list_keeps_discovery_labels(self) -> None:
        raw = [
            {
                "Id": "abc",
                "Names": ["/dashboard"],
                "Image": "dashboard:local",
                "ImageID": "sha256:fff",
                "Command": COMMAND,
                "Created": 1700000000,
                "Ports": [],
                "Labels": {
                    "com.docker.compose.service": "dashboard",
                    "not-a-string": {"nested": SECRET},
                },
                "State": "running",
                "Status": "Up",
                "HostConfig": {
                    "NetworkMode": "freedriver-web_default",
                    "Binds": [MOUNT],
                    "Privileged": True,
                },
                "NetworkSettings": {
                    "Networks": {
                        "freedriver-web_default": {
                            "NetworkID": "net1",
                            "IPAddress": "172.18.0.2",
                            "DriverOpts": {"secret": SECRET},
                            "Aliases": [SECRET],
                        }
                    }
                },
                "Mounts": [{"Source": MOUNT, "Destination": "/run/secrets"}],
                "Env": [SECRET],
            }
        ]
        cleaned = proxy.redact_list(raw)
        encoded = json.dumps(cleaned)
        self.assertNotIn(SECRET, encoded)
        self.assertNotIn(MOUNT, encoded)
        self.assertNotIn(COMMAND, encoded)
        self.assertNotIn("Privileged", encoded)
        self.assertEqual(cleaned[0]["Names"], ["/dashboard"])
        self.assertEqual(cleaned[0]["Labels"]["com.docker.compose.service"], "dashboard")
        self.assertEqual(cleaned[0]["HostConfig"], {"NetworkMode": "freedriver-web_default"})
        self.assertEqual(
            cleaned[0]["NetworkSettings"]["Networks"]["freedriver-web_default"]["IPAddress"],
            "172.18.0.2",
        )


class _Upstream:
    def __init__(self, path: str) -> None:
        self.path = path
        self.requests: list[tuple[str, str]] = []
        self.lock = threading.Lock()
        self.slow_logs = threading.Event()
        self.got_first_log_byte = threading.Event()
        self._stop = threading.Event()
        self._sock = socket.socket(socket.AF_UNIX)
        if os.path.exists(path):
            os.unlink(path)
        self._sock.bind(path)
        self._sock.listen(16)
        self._sock.settimeout(0.2)
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _ = self._sock.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn: socket.socket) -> None:
        try:
            data = bytearray()
            conn.settimeout(2)
            while b"\r\n\r\n" not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    return
                data.extend(chunk)
            line = bytes(data).split(b"\r\n", 1)[0].decode()
            method, target, _ = line.split(" ")
            path = target.split("?", 1)[0]
            with self.lock:
                self.requests.append((method, target))
            if path.endswith("/_ping"):
                body = b"" if method == "HEAD" else b"OK"
                self._send(
                    conn,
                    "200 OK",
                    body,
                    extra="Api-Version: 1.44\r\nOstype: linux\r\nDocker-Experimental: false\r\n",
                )
                return
            if path.endswith("/containers/json"):
                payload = [
                    {
                        "Id": "abc",
                        "Names": ["/dashboard"],
                        "Image": "dashboard:local",
                        "Command": COMMAND,
                        "Created": 10,
                        "Labels": {"com.docker.compose.service": "dashboard"},
                        "State": "running",
                        "Status": "Up",
                        "HostConfig": {"NetworkMode": "freedriver-web_default"},
                        "NetworkSettings": {
                            "Networks": {
                                "freedriver-web_default": {
                                    "IPAddress": "172.18.0.2",
                                    "DriverOpts": {"secret": SECRET},
                                }
                            }
                        },
                        "Mounts": [{"Source": MOUNT}],
                        "Env": [SECRET],
                    }
                ]
                self._send(conn, "200 OK", json.dumps(payload).encode(), content_type="application/json")
                return
            if path.endswith("/json"):
                payload = {
                    "Id": "abc",
                    "Name": "/dashboard",
                    "State": {
                        "Status": "running",
                        "Running": True,
                        "FinishedAt": "0001-01-01T00:00:00Z",
                        "StartedAt": "2026-10-20T00:00:00Z",
                        "Pid": 9,
                    },
                    "Config": {"Tty": False, "Env": [f"TOKEN={SECRET}"]},
                    "HostConfig": {"Privileged": True, "Binds": [MOUNT]},
                    "Mounts": [{"Source": MOUNT}],
                }
                self._send(conn, "200 OK", json.dumps(payload).encode(), content_type="application/json")
                return
            if path.endswith("/logs"):
                self._send_slow_logs(conn)
                return
            self._send(conn, "500 Internal Server Error", b'{"message":"unexpected"}', content_type="application/json")
        finally:
            conn.close()

    def _send_slow_logs(self, conn: socket.socket) -> None:
        head = (
            "HTTP/1.1 200 OK\r\n"
            "Content-Type: application/vnd.docker.raw-stream\r\n"
            "Transfer-Encoding: chunked\r\n"
            "\r\n"
            "5\r\nhello\r\n"
        )
        conn.sendall(head.encode())
        self.slow_logs.set()
        if not self.got_first_log_byte.wait(2):
            return
        conn.sendall(b"5\r\nworld\r\n0\r\n\r\n")

    def _send(self, conn: socket.socket, status: str, body: bytes, extra: str = "", content_type: str = "text/plain") -> None:
        head = (
            f"HTTP/1.1 {status}\r\n"
            f"Content-Type: {content_type}\r\n"
            f"Content-Length: {len(body)}\r\n"
            f"{extra}"
            "Connection: keep-alive\r\n"
            "\r\n"
        )
        conn.sendall(head.encode("ascii") + body)

    def close(self) -> None:
        self._stop.set()
        self._sock.close()
        self._thread.join(timeout=2)


def _request(sock_path: str, method: str, target: str) -> tuple[int, dict[str, str], bytes]:
    sock = socket.socket(socket.AF_UNIX)
    sock.settimeout(3)
    sock.connect(sock_path)
    sock.sendall(f"{method} {target} HTTP/1.1\r\nHost: docker\r\nConnection: close\r\n\r\n".encode())
    data = bytearray()
    while True:
        try:
            chunk = sock.recv(65536)
        except TimeoutError:
            break
        if not chunk:
            break
        data.extend(chunk)
        if b"\r\n\r\n" in data and method == "HEAD":
            break
    sock.close()
    raw = bytes(data)
    head, _, body = raw.partition(b"\r\n\r\n")
    lines = head.decode("iso-8859-1").split("\r\n")
    status = int(lines[0].split(" ")[1])
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.lower()] = value.strip()
    if method != "HEAD" and "content-length" in headers:
        length = int(headers["content-length"])
        body = body[:length]
    return status, headers, body


class ProxySocketTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.upstream_path = str(root / "docker.sock")
        self.listen_path = str(root / "proxy.sock")
        self.upstream = _Upstream(self.upstream_path)
        self.server = proxy.make_server(self.listen_path, self.upstream_path)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.upstream.close()
        self.tmp.cleanup()

    def _seen(self) -> list[tuple[str, str]]:
        with self.upstream.lock:
            return list(self.upstream.requests)

    def test_ping_forwards_api_version_header(self) -> None:
        status, headers, body = _request(self.listen_path, "GET", "/_ping")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"OK")
        self.assertEqual(headers.get("api-version"), "1.44")
        self.assertEqual(headers.get("ostype"), "linux")
        status, headers, body = _request(self.listen_path, "HEAD", "/v1.44/_ping")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"")
        self.assertEqual(headers.get("api-version"), "1.44")
        self.assertIn(("HEAD", "/v1.44/_ping"), self._seen())

    def test_list_strips_secrets_and_mounts(self) -> None:
        status, _, body = _request(self.listen_path, "GET", "/v1.44/containers/json?all=1")
        self.assertEqual(status, 200)
        text = body.decode()
        self.assertNotIn(SECRET, text)
        self.assertNotIn(MOUNT, text)
        self.assertNotIn(COMMAND, text)
        payload = json.loads(body)
        self.assertEqual(payload[0]["Labels"]["com.docker.compose.service"], "dashboard")
        self.assertEqual(payload[0]["Names"], ["/dashboard"])
        self.assertEqual(payload[0]["HostConfig"]["NetworkMode"], "freedriver-web_default")
        self.assertIn(("GET", "/v1.44/containers/json?all=1"), self._seen())

    def test_inspect_strips_env(self) -> None:
        status, _, body = _request(self.listen_path, "GET", "/v1.44/containers/abc/json")
        self.assertEqual(status, 200)
        text = body.decode()
        self.assertNotIn(SECRET, text)
        self.assertNotIn(MOUNT, text)
        self.assertNotIn("Privileged", text)
        payload = json.loads(body)
        self.assertTrue(payload["State"]["Running"])
        self.assertEqual(payload["State"]["FinishedAt"], "0001-01-01T00:00:00Z")
        self.assertFalse(payload["Config"]["Tty"])

    def test_logs_stream_without_buffering_until_end(self) -> None:
        sock = socket.socket(socket.AF_UNIX)
        sock.settimeout(2)
        sock.connect(self.listen_path)
        sock.sendall(
            b"GET /v1.44/containers/abc/logs?follow=1&stdout=1&stderr=1&timestamps=1 HTTP/1.1\r\n"
            b"Host: docker\r\nConnection: close\r\n\r\n"
        )
        self.assertTrue(self.upstream.slow_logs.wait(2))
        data = bytearray()
        deadline = time.monotonic() + 1
        while b"hello" not in data and time.monotonic() < deadline:
            data.extend(sock.recv(4096))
        self.upstream.got_first_log_byte.set()
        while b"world" not in data and time.monotonic() < deadline + 1:
            data.extend(sock.recv(4096))
        sock.close()
        text = bytes(data).decode("iso-8859-1")
        self.assertIn("hello", text)
        self.assertIn("world", text)
        self.assertIn("application/vnd.docker.raw-stream", text)
        self.assertTrue(any(method == "GET" and "/logs?" in target for method, target in self._seen()))

    def test_denied_calls_do_not_reach_the_daemon(self) -> None:
        before = len(self._seen())
        denied = [
            ("POST", "/v1.44/containers/create"),
            ("POST", "/v1.44/containers/abc/exec"),
            ("GET", "/v1.44/containers/abc/exec"),
            ("GET", "/v1.44/containers/abc/attach"),
            ("GET", "/v1.44/info"),
            ("GET", "/v1.44/version"),
            ("DELETE", "/v1.44/containers/abc"),
            ("GET", "/v1.44/events"),
        ]
        for method, target in denied:
            status, _, body = _request(self.listen_path, method, target)
            self.assertEqual(status, 403, target)
            self.assertIn(b"forbidden", body)
        self.assertEqual(len(self._seen()), before)

    def test_network_list_is_local_and_empty(self) -> None:
        before = len(self._seen())
        status, _, body = _request(self.listen_path, "GET", "/v1.44/networks")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), [])
        self.assertEqual(len(self._seen()), before)

    def test_healthcheck_pings_through_the_proxy(self) -> None:
        self.assertEqual(proxy.healthcheck(self.listen_path), 0)


class ComposeWiringTest(unittest.TestCase):
    def test_only_the_proxy_mounts_the_docker_socket(self) -> None:
        compose = (REPO / "docker-compose.yml").read_text()
        services = _compose_services(compose)
        self.assertIn("docker-logs-proxy", services)
        self.assertIn("alloy", services)
        sock_services = [name for name, block in services.items() if "docker.sock" in block]
        self.assertEqual(sock_services, ["docker-logs-proxy"])
        proxy_block = services["docker-logs-proxy"]
        alloy_block = services["alloy"]
        self.assertIn("/var/run/docker.sock:/var/run/docker.sock:ro", proxy_block)
        self.assertIn("docker-logs-sock:/run/docker-logs", proxy_block)
        self.assertNotIn("\n    ports:", proxy_block)
        self.assertNotIn("docker.sock", alloy_block)
        self.assertIn("docker-logs-sock:/run/docker-logs:ro", alloy_block)
        self.assertIn("docker-logs-proxy:", alloy_block)
        self.assertIn("condition: service_healthy", alloy_block)
        self.assertTrue(compose.strip().endswith("docker-logs-sock:") or "\ndocker-logs-sock:\n" in compose + "\n")
        alloy_config = (REPO / "observability/alloy/config.alloy").read_text()
        self.assertNotIn("/var/run/docker.sock", alloy_config)
        self.assertEqual(alloy_config.count("unix:///run/docker-logs/docker.sock"), 2)
        self.assertNotIn("docker.sock", (REPO / "Caddyfile").read_text())


if __name__ == "__main__":
    unittest.main()
