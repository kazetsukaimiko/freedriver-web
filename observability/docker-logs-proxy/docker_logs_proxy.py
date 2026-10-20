#!/usr/bin/env python3
"""Unix-socket proxy that exposes only the Docker calls Alloy uses for logs.

The real Docker socket stays mounted on this process. Alloy connects to the
proxy socket. Container inspect is forwarded because loki.source.docker in
Alloy v1.18.1 reads State.Running, State.FinishedAt, and Config.Tty, but the
JSON is rebuilt from those fields so environment, host config, and mounts
never leave the proxy. discovery.docker also lists networks; that response is
an empty array and is not sent to the daemon. Container name and compose
labels come from the container list.
"""

from __future__ import annotations

import json
import os
import re
import select
import socket
import socketserver
import sys

LISTEN_DEFAULT = "/run/docker-logs/docker.sock"
UPSTREAM_DEFAULT = "/var/run/docker.sock"
MAX_HEADER = 64 * 1024
MAX_BODY = 16 * 1024 * 1024
SOCKET_MODE = 0o660

_VERSION = r"(?:/v1\.[0-9]{1,3})?"
_ID = r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"
_ROUTES = (
    ("ping", re.compile(rf"^{_VERSION}/_ping$")),
    ("list", re.compile(rf"^{_VERSION}/containers/json$")),
    ("inspect", re.compile(rf"^{_VERSION}/containers/{_ID}/json$")),
    ("logs", re.compile(rf"^{_VERSION}/containers/{_ID}/logs$")),
    ("networks", re.compile(rf"^{_VERSION}/networks$")),
)
_HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
}
_ZERO_TIME = "0001-01-01T00:00:00Z"


def classify(method: str, path: str) -> str | None:
    """Return the allowlisted route, or None when the call must not be forwarded."""
    if method == "HEAD" and path.endswith("/_ping"):
        for name, pattern in _ROUTES:
            if name == "ping" and pattern.match(path):
                return "ping"
        return None
    if method != "GET":
        return None
    if ".." in path or "%" in path or "\\" in path:
        return None
    for name, pattern in _ROUTES:
        if pattern.match(path):
            return name
    return None


def redact_list(payload: object) -> list:
    """Keep the list fields discovery.docker reads. Drop args and mount paths."""
    if not isinstance(payload, list):
        raise ValueError("container list is not an array")
    cleaned = []
    for item in payload:
        if not isinstance(item, dict):
            raise ValueError("container list entry is not an object")
        host = item.get("HostConfig")
        network_mode = ""
        if isinstance(host, dict) and isinstance(host.get("NetworkMode"), str):
            network_mode = host["NetworkMode"]
        labels = item.get("Labels")
        kept_labels = {}
        if isinstance(labels, dict):
            kept_labels = {
                key: value
                for key, value in labels.items()
                if isinstance(key, str) and isinstance(value, str)
            }
        entry = {
            "Id": item.get("Id") if isinstance(item.get("Id"), str) else "",
            "Names": _string_list(item.get("Names")),
            "Image": item.get("Image") if isinstance(item.get("Image"), str) else "",
            "ImageID": item.get("ImageID") if isinstance(item.get("ImageID"), str) else "",
            "Created": item.get("Created") if _json_number(item.get("Created")) else 0,
            "Ports": _redact_ports(item.get("Ports")),
            "Labels": kept_labels,
            "State": item.get("State") if isinstance(item.get("State"), str) else "",
            "Status": item.get("Status") if isinstance(item.get("Status"), str) else "",
            "HostConfig": {"NetworkMode": network_mode},
            # Always an object. A null NetworkSettings makes discovery panic and
            # drop every container, not just the one that lacked a network.
            "NetworkSettings": _redact_network_settings(item.get("NetworkSettings")),
        }
        cleaned.append(entry)
    return cleaned


def _redact_network_settings(value: object) -> dict:
    """Keep the endpoint fields discovery reads: id and IP. Drop the rest."""
    networks: dict[str, dict[str, str]] = {}
    raw = value.get("Networks") if isinstance(value, dict) else None
    if isinstance(raw, dict):
        for name, endpoint in raw.items():
            if not isinstance(name, str):
                continue
            kept: dict[str, str] = {}
            if isinstance(endpoint, dict):
                for key in ("NetworkID", "IPAddress", "GlobalIPv6Address"):
                    item = endpoint.get(key)
                    if isinstance(item, str):
                        kept[key] = item
            networks[name] = kept
    return {"Networks": networks}


def _redact_ports(value: object) -> list:
    if not isinstance(value, list):
        return []
    ports = []
    for item in value:
        if not isinstance(item, dict):
            continue
        kept = {}
        for key in ("IP", "PrivatePort", "PublicPort", "Type"):
            field = item.get(key)
            if isinstance(field, str) or _json_number(field):
                kept[key] = field
        ports.append(kept)
    return ports


def redact_inspect(payload: object) -> dict:
    """Rebuild inspect with only the fields the log tailer reads."""
    if not isinstance(payload, dict):
        raise ValueError("inspect is not an object")
    state = payload.get("State") if isinstance(payload.get("State"), dict) else {}
    config = payload.get("Config") if isinstance(payload.get("Config"), dict) else {}
    finished = state.get("FinishedAt")
    started = state.get("StartedAt")
    exit_code = state.get("ExitCode")
    return {
        "Id": payload.get("Id") if isinstance(payload.get("Id"), str) else "",
        "Name": payload.get("Name") if isinstance(payload.get("Name"), str) else "",
        "State": {
            "Status": state.get("Status") if isinstance(state.get("Status"), str) else "",
            "Running": bool(state.get("Running")),
            "Paused": bool(state.get("Paused")),
            "Restarting": bool(state.get("Restarting")),
            "OOMKilled": bool(state.get("OOMKilled")),
            "Dead": bool(state.get("Dead")),
            "ExitCode": exit_code if _json_number(exit_code) else 0,
            "Error": "",
            "StartedAt": started if isinstance(started, str) else _ZERO_TIME,
            "FinishedAt": finished if isinstance(finished, str) else _ZERO_TIME,
        },
        "Config": {"Tty": bool(config.get("Tty"))},
    }


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def _json_number(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


class ProxyServer(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True
    request_queue_size = 32

    def server_bind(self) -> None:
        directory = os.path.dirname(self.server_address)
        if directory:
            os.makedirs(directory, exist_ok=True)
        if os.path.exists(self.server_address):
            os.unlink(self.server_address)
        super().server_bind()
        os.chmod(self.server_address, SOCKET_MODE)


def make_server(listen_path: str, upstream_path: str) -> ProxyServer:
    class Handler(socketserver.BaseRequestHandler):
        def handle(self) -> None:
            try:
                handle_client(self.request, upstream_path)
            except Exception as exc:
                _log(f"client closed: {exc.__class__.__name__}")

    return ProxyServer(listen_path, Handler)


def handle_client(client: socket.socket, upstream_path: str) -> None:
    client.settimeout(30)
    try:
        method, target = _read_request(client)
    except ValueError:
        _send_json(client, 400, "bad request")
        return
    except (ConnectionError, TimeoutError, EOFError, socket.timeout):
        return

    path, query = _split_target(target)
    if path is None:
        _send_json(client, 400, "bad request")
        return
    kind = classify(method, path)
    if kind is None:
        _log(f"deny {method} {path}")
        _send_json(client, 403, "forbidden")
        return
    if kind == "networks":
        # discovery.docker fails the whole refresh if network list errors.
        # Names and compose labels come from the container list, so an empty
        # network list keeps those labels without opening the network API.
        _send_json_body(client, 200, b"[]")
        return

    client.settimeout(None)
    upstream = socket.socket(socket.AF_UNIX)
    try:
        upstream.settimeout(30)
        upstream.connect(upstream_path)
        upstream.sendall(_upstream_request(method, path, query))
        upstream.settimeout(None)
        if kind == "logs":
            _relay_logs(client, upstream)
            return
        transform = None
        if kind == "list":
            transform = _transform_json(redact_list)
        elif kind == "inspect":
            transform = _transform_json(redact_inspect)
        _relay_finite(client, upstream, method, transform)
    except (ConnectionError, FileNotFoundError, TimeoutError, socket.timeout, OSError) as exc:
        _log(f"upstream {path}: {exc.__class__.__name__}")
        _send_json(client, 502, "upstream unavailable")
    finally:
        upstream.close()


def _read_request(client: socket.socket) -> tuple[str, str]:
    buf = bytearray()
    while b"\r\n\r\n" not in buf:
        chunk = client.recv(4096)
        if not chunk:
            raise EOFError
        buf.extend(chunk)
        if len(buf) > MAX_HEADER:
            raise ValueError("request too large")
    head, _, extra = bytes(buf).partition(b"\r\n\r\n")
    if extra:
        raise ValueError("pipelined request")
    lines = head.decode("iso-8859-1").split("\r\n")
    parts = lines[0].split(" ")
    if len(parts) != 3 or parts[2] not in {"HTTP/1.0", "HTTP/1.1"}:
        raise ValueError("bad request line")
    method, target = parts[0].upper(), parts[1]
    if len(target) > 8192 or any(char in target for char in "\r\n "):
        raise ValueError("bad target")
    headers: dict[str, list[str]] = {}
    for line in lines[1:]:
        if not line or line[:1] in " \t" or ":" not in line:
            raise ValueError("bad header")
        name, value = line.split(":", 1)
        headers.setdefault(name.lower(), []).append(value.strip())
    if "transfer-encoding" in headers:
        raise ValueError("request body")
    if "content-length" in headers:
        try:
            length = int(headers["content-length"][-1])
        except ValueError as exc:
            raise ValueError("bad content-length") from exc
        if length != 0:
            raise ValueError("request body")
    return method, target


def _split_target(target: str) -> tuple[str | None, str]:
    if not target.startswith("/") or target.startswith("//"):
        return None, ""
    path, _, query = target.partition("?")
    if "#" in path or "#" in query:
        return None, ""
    return path, query


def _upstream_request(method: str, path: str, query: str) -> bytes:
    target = path if not query else f"{path}?{query}"
    lines = [
        f"{method} {target} HTTP/1.1",
        "Host: docker",
        "User-Agent: docker-logs-proxy",
        "Connection: close",
        "",
        "",
    ]
    return "\r\n".join(lines).encode("ascii")


def _relay_logs(client: socket.socket, upstream: socket.socket) -> None:
    """Pass the log response through without buffering the stream."""
    buf = bytearray()
    head = _read_head(upstream, buf)
    status, headers = _parse_head(head)
    client.sendall(_rebuild_headers(status, headers, keep_framing=True))
    if buf:
        client.sendall(buf)
    _splice(client, upstream)


def _relay_finite(client: socket.socket, upstream: socket.socket, method: str, transform) -> None:
    buf = bytearray()
    head = _read_head(upstream, buf)
    status, headers = _parse_head(head)
    code = _status_code(status)
    if method == "HEAD" or code in {204, 304}:
        client.sendall(_rebuild_headers(status, headers, content_length=0))
        return
    body = _read_body(upstream, buf, headers)
    if transform is not None and code == 200:
        try:
            body = transform(body)
        except (ValueError, json.JSONDecodeError):
            _log(f"reject malformed {status}")
            _send_json(client, 502, "upstream response rejected")
            return
        _send_json_body(client, 200, body)
        return
    client.sendall(_rebuild_headers(status, headers, content_length=len(body)))
    client.sendall(body)


def _transform_json(redactor):
    def transform(body: bytes) -> bytes:
        redacted = redactor(json.loads(body))
        encoded = json.dumps(redacted, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return encoded

    return transform


def _read_head(sock: socket.socket, buf: bytearray) -> bytes:
    while b"\r\n\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise EOFError("upstream closed before headers")
        buf.extend(chunk)
        if len(buf) > MAX_HEADER:
            raise ValueError("upstream headers too large")
    raw = bytes(buf)
    index = raw.find(b"\r\n\r\n")
    head = raw[:index]
    del buf[: index + 4]
    return head


def _parse_head(head: bytes) -> tuple[str, dict[str, list[str]]]:
    lines = head.decode("iso-8859-1").split("\r\n")
    if not lines or not lines[0].startswith("HTTP/"):
        raise ValueError("bad upstream status")
    headers: dict[str, list[str]] = {}
    for line in lines[1:]:
        if not line or ":" not in line:
            continue
        name, value = line.split(":", 1)
        headers.setdefault(name, []).append(value.strip())
    return lines[0], headers


def _status_code(status: str) -> int:
    parts = status.split(" ")
    if len(parts) < 2:
        raise ValueError("bad upstream status")
    return int(parts[1])


def _header_values(headers: dict[str, list[str]], name: str) -> list[str]:
    want = name.lower()
    for key, values in headers.items():
        if key.lower() == want:
            return values
    return []


def _read_body(sock: socket.socket, buf: bytearray, headers: dict[str, list[str]]) -> bytes:
    if any(value.lower() != "identity" for value in _header_values(headers, "transfer-encoding")):
        if not any("chunked" in value.lower() for value in _header_values(headers, "transfer-encoding")):
            raise ValueError("unsupported transfer encoding")
        return _read_chunked(sock, buf)
    lengths = _header_values(headers, "content-length")
    if lengths:
        length = int(lengths[-1])
        if length < 0 or length > MAX_BODY:
            raise ValueError("upstream body too large")
        return _read_exact(sock, buf, length)
    # No length and no chunking: read until the daemon closes (Connection: close).
    body = bytearray(buf)
    del buf[:]
    while len(body) <= MAX_BODY:
        chunk = sock.recv(65536)
        if not chunk:
            return bytes(body)
        body.extend(chunk)
    raise ValueError("upstream body too large")


def _read_exact(sock: socket.socket, buf: bytearray, length: int) -> bytes:
    while len(buf) < length:
        chunk = sock.recv(min(65536, length - len(buf)))
        if not chunk:
            raise EOFError("short upstream body")
        buf.extend(chunk)
    body = bytes(buf[:length])
    del buf[:length]
    return body


def _read_chunked(sock: socket.socket, buf: bytearray) -> bytes:
    body = bytearray()
    while True:
        line = _read_line(sock, buf)
        size_token = line.split(b";", 1)[0].strip()
        try:
            size = int(size_token, 16)
        except ValueError as exc:
            raise ValueError("bad chunk size") from exc
        if size == 0:
            while _read_line(sock, buf) != b"":
                pass
            return bytes(body)
        if size < 0 or len(body) + size > MAX_BODY:
            raise ValueError("upstream body too large")
        body.extend(_read_exact(sock, buf, size))
        if _read_exact(sock, buf, 2) != b"\r\n":
            raise ValueError("bad chunk terminator")


def _read_line(sock: socket.socket, buf: bytearray) -> bytes:
    while b"\r\n" not in buf:
        chunk = sock.recv(4096)
        if not chunk:
            raise EOFError("short chunk line")
        buf.extend(chunk)
        if len(buf) > MAX_BODY:
            raise ValueError("upstream body too large")
    raw = bytes(buf)
    line, _, rest = raw.partition(b"\r\n")
    buf[:] = rest
    return line


def _rebuild_headers(
    status: str,
    headers: dict[str, list[str]],
    content_length: int | None = None,
    keep_framing: bool = False,
) -> bytes:
    lines = [status]
    chunked = any("chunked" in value.lower() for value in _header_values(headers, "transfer-encoding"))
    for name, values in headers.items():
        if name.lower() in _HOP_BY_HOP or name.lower() == "content-length":
            continue
        for value in values:
            if "\r" in value or "\n" in value or "\r" in name or "\n" in name:
                continue
            lines.append(f"{name}: {value}")
    lines.append("Connection: close")
    if keep_framing and chunked:
        lines.append("Transfer-Encoding: chunked")
    elif keep_framing and _header_values(headers, "content-length"):
        lines.append(f"Content-Length: {_header_values(headers, 'content-length')[-1]}")
    elif content_length is not None:
        lines.append(f"Content-Length: {content_length}")
    return ("\r\n".join(lines) + "\r\n\r\n").encode("iso-8859-1")


def _splice(client: socket.socket, upstream: socket.socket) -> None:
    """Copy the upstream stream until either side closes. Do not buffer it."""
    sockets = [client, upstream]
    while True:
        readable, _, _ = select.select(sockets, [], [])
        if client in readable:
            try:
                data = client.recv(1)
            except OSError:
                return
            # A follow stream is one request. Bytes or EOF from the client end it.
            if data:
                _log("client wrote during log stream; closing")
            return
        if upstream in readable:
            try:
                data = upstream.recv(65536)
            except OSError:
                return
            if not data:
                return
            try:
                client.sendall(data)
            except OSError:
                return


def _send_json(sock: socket.socket, status: int, message: str) -> None:
    phrases = {200: "OK", 400: "Bad Request", 403: "Forbidden", 502: "Bad Gateway"}
    body = json.dumps({"message": f"docker-logs-proxy: {message}"}, separators=(",", ":")).encode()
    _send_bytes(sock, status, phrases.get(status, "Error"), "application/json", body)


def _send_json_body(sock: socket.socket, status: int, body: bytes) -> None:
    phrases = {200: "OK"}
    _send_bytes(sock, status, phrases.get(status, "OK"), "application/json", body)


def _send_bytes(sock: socket.socket, status: int, phrase: str, content_type: str, body: bytes) -> None:
    head = (
        f"HTTP/1.1 {status} {phrase}\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(body)}\r\n"
        "Connection: close\r\n"
        "\r\n"
    )
    try:
        sock.sendall(head.encode("ascii") + body)
    except OSError:
        return


def healthcheck(listen_path: str) -> int:
    sock = socket.socket(socket.AF_UNIX)
    sock.settimeout(3)
    try:
        sock.connect(listen_path)
        sock.sendall(b"GET /_ping HTTP/1.1\r\nHost: docker\r\nConnection: close\r\n\r\n")
        data = bytearray()
        while b"\r\n" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data.extend(chunk)
        status = bytes(data).split(b"\r\n", 1)[0]
        return 0 if b" 200 " in status else 1
    except OSError:
        return 1
    finally:
        sock.close()


def _log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def main(argv: list[str]) -> int:
    listen = os.environ.get("LISTEN_SOCK", LISTEN_DEFAULT)
    upstream = os.environ.get("UPSTREAM_SOCK", UPSTREAM_DEFAULT)
    if "--healthcheck" in argv[1:]:
        return healthcheck(listen)
    server = make_server(listen, upstream)
    _log(f"listening on {listen}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
