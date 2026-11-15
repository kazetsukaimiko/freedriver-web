#!/usr/bin/env python3
"""MQTT house responder for the compose smoke job (#533).

Connects as the provisioned house, publishes appliance state (QoS 1, retain
false: production mosquitto.conf sets retain_available false), and answers each
command with a new state that carries that command id.
"""
from __future__ import annotations

import json
import os
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import paho.mqtt.client as mqtt

HOST = os.environ.get("MQTT_HOST", "mosquitto")
PORT = int(os.environ.get("MQTT_PORT", "8883"))
CA = os.environ.get("MQTT_CA", "/certs/ca.crt")
INSTANCE_ID = os.environ.get("INSTANCE_ID", "").strip()
PASSWORD = os.environ.get("MQTT_PASSWORD", "")
INSTANCE_NAME = os.environ.get("INSTANCE_NAME", "Fixture")
NAMES = [name.strip() for name in os.environ.get("APPLIANCES", "hallway,fridge").split(",") if name.strip()]

if not INSTANCE_ID or INSTANCE_ID == "pending" or not PASSWORD or PASSWORD == "pending":
    raise SystemExit("fixture-house needs INSTANCE_ID and MQTT_PASSWORD")
if not NAMES:
    raise SystemExit("fixture-house needs at least one appliance name")

APPLIANCES_TOPIC = f"freedriver/v1/{INSTANCE_ID}/appliances"
COMMANDS_TOPIC = f"freedriver/v1/{INSTANCE_ID}/commands"

LOCK = threading.Lock()
STATES = {name: True for name in NAMES}
COMMAND_IDS: list[str] = []
CONNECTED = threading.Event()
CLIENT: mqtt.Client | None = None


def state_payload(applied: str | None = None) -> str:
    with LOCK:
        appliances = [{"applianceName": name, "state": STATES[name]} for name in NAMES]
    return json.dumps(
        {
            "instanceName": INSTANCE_NAME,
            "appliedCommandId": applied,
            "appliances": appliances,
        }
    )


def publish_state(applied: str | None = None) -> None:
    client = CLIENT
    if client is None:
        return
    client.publish(APPLIANCES_TOPIC, state_payload(applied), qos=1, retain=False)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        return

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            if not CONNECTED.is_set():
                self.send_response(503)
                self.end_headers()
                return
            body = b"ok"
            content = "text/plain"
        elif self.path == "/commands":
            with LOCK:
                body = json.dumps({"count": len(COMMAND_IDS), "ids": list(COMMAND_IDS)}).encode()
            content = "application/json"
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", content)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def on_connect(client: mqtt.Client, userdata: object, flags: object, reason_code: object, properties: object = None) -> None:
    code = getattr(reason_code, "value", reason_code)
    if int(code) != 0:
        print(f"fixture-house mqtt connect failed: {reason_code}", file=sys.stderr, flush=True)
        os._exit(1)
    client.subscribe(COMMANDS_TOPIC, qos=1)
    CONNECTED.set()
    publish_state()
    print("fixture-house connected", flush=True)


def on_message(client: mqtt.Client, userdata: object, msg: mqtt.MQTTMessage) -> None:
    try:
        payload = json.loads(msg.payload.decode("utf-8"))
        name = payload["applianceName"]
        command_id = payload["commandId"]
        state = payload["state"]
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"fixture-house ignored command: {exc}", file=sys.stderr, flush=True)
        return
    with LOCK:
        if name not in STATES:
            print(f"fixture-house unknown appliance {name}", file=sys.stderr, flush=True)
            return
        STATES[name] = bool(state)
        COMMAND_IDS.append(str(command_id))
    publish_state(str(command_id))


def republish() -> None:
    while True:
        time.sleep(2)
        if CONNECTED.is_set():
            publish_state()


def main() -> int:
    global CLIENT
    threading.Thread(
        target=lambda: ThreadingHTTPServer(("127.0.0.1", 8080), Handler).serve_forever(),
        daemon=True,
    ).start()
    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"fixture-{INSTANCE_ID[:8]}",
        protocol=mqtt.MQTTv311,
    )
    client.username_pw_set(INSTANCE_ID, PASSWORD)
    client.tls_set(ca_certs=CA, cert_reqs=ssl.CERT_REQUIRED, tls_version=ssl.PROTOCOL_TLS_CLIENT)
    client.on_connect = on_connect
    client.on_message = on_message
    CLIENT = client
    threading.Thread(target=republish, daemon=True).start()
    client.connect(HOST, PORT, keepalive=30)
    client.loop_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
