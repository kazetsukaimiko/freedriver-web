#!/usr/bin/env bash
# Fixture for the two Mosquitto sockets. No broker password is printed.
# Cert checks always run. A Mosquitto handshake runs when docker is available
# (required when CI=true).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ISSUE="${ROOT}/scripts/issue-mqtt-internal-cert.sh"
SYNC="${ROOT}/scripts/sync-mosquitto-le-cert.sh"
WORKDIR="$(mktemp -d)"
chmod 700 "$WORKDIR"

docker_name=""
docker_net=""
tls_server_pid=""

cleanup() {
  if [[ -n "$docker_name" ]]; then
    docker rm -f "$docker_name" >/dev/null 2>&1 || true
  fi
  if [[ -n "$docker_net" ]]; then
    docker network rm "$docker_net" >/dev/null 2>&1 || true
  fi
  if [[ -n "$tls_server_pid" ]]; then
    kill "$tls_server_pid" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

cd "$ROOT"

python3 - <<'PY'
import re
from pathlib import Path

def service(text, name):
    key = f"  {name}:\n"
    i = text.find(key)
    if i < 0:
        raise SystemExit(f"missing service {name}")
    rest = text[i:]
    nxt = re.search(r"\n  [A-Za-z0-9_-]+:\n", rest[1:])
    return rest if nxt is None else rest[: nxt.start() + 1]

compose = Path("docker-compose.yml").read_text()
mosquitto = service(compose, "mosquitto")
dashboard = service(compose, "dashboard")
if "8883:8883" in mosquitto:
    raise SystemExit("container port 8883 must not be published")
if '"8883:8884"' not in mosquitto:
    raise SystemExit("host 8883 must publish container 8884")
if re.search(r'^\s+-\s+.*mqtt-internal', mosquitto, re.M) or re.search(r'^\s+-\s+.*ca\.crt', mosquitto, re.M):
    raise SystemExit("do not mount the internal CA into mosquitto")
if re.search(r'^\s+-\s+.*mqtt-internal', dashboard, re.M) or re.search(r'^\s+-\s+.*ca\.crt', dashboard, re.M):
    raise SystemExit("do not mount ca.crt into the API")
if re.search(r'"1883:|"1883', compose):
    raise SystemExit("do not open 1883")

conf_lines = []
for raw in Path("mosquitto/mosquitto.conf").read_text().splitlines():
    stripped = raw.split("#", 1)[0].strip()
    if stripped:
        conf_lines.append(stripped)
if any("listener_allow_anonymous" in line for line in conf_lines):
    raise SystemExit("do not set listener_allow_anonymous")
if any(line == "per_listener_settings true" for line in conf_lines):
    raise SystemExit("do not enable per_listener_settings")
first_listener = next(i for i, line in enumerate(conf_lines) if line.startswith("listener "))
globals_ = conf_lines[:first_listener]
for required in (
    "allow_anonymous false",
    "password_file /mosquitto/config/secrets/passwd",
    "acl_file /mosquitto/config/secrets/acl",
    "persistence true",
):
    if required not in globals_:
        raise SystemExit(f"missing global setting before listeners: {required}")

listeners = []
current = None
for line in conf_lines[first_listener:]:
    if line.startswith("listener "):
        if current:
            listeners.append(current)
        current = {"port": line.split()[1], "lines": []}
    else:
        current["lines"].append(line)
if current:
    listeners.append(current)
ports = [item["port"] for item in listeners]
if ports != ["8883", "8884"]:
    raise SystemExit(f"listeners must be 8883 then 8884, got {ports}")
internal = "\n".join(listeners[0]["lines"])
public = "\n".join(listeners[1]["lines"])
if "certfile /mosquitto/config/secrets/internal.crt" not in internal:
    raise SystemExit("8883 must present internal.crt")
if "keyfile /mosquitto/config/secrets/internal.key" not in internal:
    raise SystemExit("8883 must use internal.key")
if "server.crt" in internal or "server.key" in internal:
    raise SystemExit("8883 must not use the public pair")
if "certfile /mosquitto/config/secrets/server.crt" not in public:
    raise SystemExit("8884 must present server.crt")
if "keyfile /mosquitto/config/secrets/server.key" not in public:
    raise SystemExit("8884 must use server.key")
if "internal." in public:
    raise SystemExit("8884 must not use the internal pair")

props = Path("java/dashboard/src/main/resources/application.properties").read_text().splitlines()
if "freedriver.appliances.live-commands=false" not in props:
    raise SystemExit("live-commands must stay false")
if any(line.startswith("freedriver.appliances.live-commands=true") for line in props):
    raise SystemExit("do not turn live-commands on")
if any(line.startswith("%prod.freedriver.appliances.live-commands=true") for line in props):
    raise SystemExit("do not turn live-commands on in prod")

provision = Path("scripts/provision-mosquitto.sh").read_text()
if "will not overwrite Let" not in provision:
    raise SystemExit("provision must still leave the public pair in place")
if 'chmod 0600 "${SECRETS}/internal.key"' not in provision:
    raise SystemExit("provision must force internal.key to 0600 after the blanket chmod")
if "issue-mqtt-internal-cert.sh" not in provision:
    raise SystemExit("provision must issue the internal certificate")
sync = Path("scripts/sync-mosquitto-le-cert.sh").read_text()
if "internal.crt" not in sync or "server.crt" not in sync:
    raise SystemExit("sync script lost its public-pair target")
if re.search(r"DEST_DIR\}/internal|DEST_DIR\}/ca", sync):
    raise SystemExit("sync must not write the internal pair or the CA")
print("config: two sockets, no API CA mount, live-commands off")
PY

SECRETS="${WORKDIR}/mosquitto"
CA_DIR="${WORKDIR}/mqtt-internal"
mkdir -p "$SECRETS" "$CA_DIR"

openssl req -x509 -newkey rsa:2048 -sha256 -days 2 -nodes \
  -keyout "${SECRETS}/server.key" \
  -out "${SECRETS}/server.crt" \
  -subj "/CN=mqtt.freedriver.io" \
  -addext "subjectAltName=DNS:mqtt.freedriver.io" \
  -addext "extendedKeyUsage=serverAuth" \
  -addext "keyUsage=digitalSignature,keyEncipherment" >/dev/null 2>&1
chmod 0600 "${SECRETS}/server.key"

MQTT_INTERNAL_DIR="$CA_DIR" MQTT_SECRETS_DIR="$SECRETS" "$ISSUE" >"${WORKDIR}/issue1.out"
if [[ -e "${CA_DIR}/ca.key" || -e "${SECRETS}/ca.key" || -e "${CA_DIR}/ca.srl" ]]; then
  echo "CA private key must not be stored" >&2
  exit 1
fi
if [[ -e "${CA_DIR}/internal.key" || -e "${CA_DIR}/server.key" ]]; then
  echo "leaf key must not be stored next to ca.crt" >&2
  exit 1
fi
# Only the CA certificate lives in the CA directory.
ca_files="$(find "$CA_DIR" -type f -print | sort)"
if [[ "$ca_files" != "${CA_DIR}/ca.crt" ]]; then
  echo "CA directory must contain only ca.crt" >&2
  printf '%s\n' "$ca_files" >&2
  exit 1
fi
openssl verify -CAfile "${CA_DIR}/ca.crt" "${SECRETS}/internal.crt" >/dev/null
if openssl verify -CAfile "${CA_DIR}/ca.crt" "${SECRETS}/server.crt" >/dev/null 2>&1; then
  echo "public leaf must not verify against the internal CA" >&2
  exit 1
fi
internal_names="$(openssl x509 -in "${SECRETS}/internal.crt" -noout -ext subjectAltName | grep -oE 'DNS:[^, ]+' | sort -u)"
if [[ "$internal_names" != "DNS:mosquitto" ]]; then
  echo "internal SAN must be DNS:mosquitto only, got ${internal_names}" >&2
  exit 1
fi
key_mode="$(stat -c %a "${SECRETS}/internal.key")"
if [[ "$key_mode" != "600" ]]; then
  echo "internal.key must be 0600, got ${key_mode}" >&2
  exit 1
fi
cmp -s "${SECRETS}/server.crt" "${SECRETS}/internal.crt" && {
  echo "internal leaf must not be the public certificate" >&2
  exit 1
}

cp "${CA_DIR}/ca.crt" "${WORKDIR}/ca.crt.first"
MQTT_INTERNAL_DIR="$CA_DIR" MQTT_SECRETS_DIR="$SECRETS" "$ISSUE" >"${WORKDIR}/issue2.out"
cmp -s "${WORKDIR}/ca.crt.first" "${CA_DIR}/ca.crt"
grep -q "Leaving existing internal listener certificate" "${WORKDIR}/issue2.out"

# A leaf that is not signed by ca.crt is replaced. The CA key is still not kept.
cp "${SECRETS}/server.crt" "${SECRETS}/internal.crt"
MQTT_INTERNAL_DIR="$CA_DIR" MQTT_SECRETS_DIR="$SECRETS" "$ISSUE" >"${WORKDIR}/issue3.out"
if cmp -s "${WORKDIR}/ca.crt.first" "${CA_DIR}/ca.crt"; then
  echo "a bad leaf must be re-issued with a new CA certificate" >&2
  exit 1
fi
openssl verify -CAfile "${CA_DIR}/ca.crt" "${SECRETS}/internal.crt" >/dev/null
internal_names="$(openssl x509 -in "${SECRETS}/internal.crt" -noout -ext subjectAltName | grep -oE 'DNS:[^, ]+' | sort -u)"
[[ "$internal_names" == "DNS:mosquitto" ]]
[[ ! -e "${CA_DIR}/ca.key" && ! -e "${SECRETS}/ca.key" ]]
[[ "$(stat -c %a "${SECRETS}/internal.key")" == "600" ]]

# Cert-sync replaces the public pair and leaves the internal pair alone.
cp "${SECRETS}/internal.crt" "${WORKDIR}/internal.crt.keep"
cp "${SECRETS}/internal.key" "${WORKDIR}/internal.key.keep"
caddy="${WORKDIR}/caddy/certificates/acme-v02.api.letsencrypt.org-directory/mqtt.freedriver.io"
mkdir -p "$caddy"
openssl req -x509 -newkey rsa:2048 -sha256 -days 2 -nodes \
  -keyout "${caddy}/mqtt.freedriver.io.key" \
  -out "${caddy}/mqtt.freedriver.io.crt" \
  -subj "/CN=mqtt.freedriver.io" \
  -addext "subjectAltName=DNS:mqtt.freedriver.io" \
  -addext "extendedKeyUsage=serverAuth" \
  -addext "keyUsage=digitalSignature,keyEncipherment" >/dev/null 2>&1
CADDY_CERTS="${WORKDIR}/caddy/certificates" DEST_DIR="$SECRETS" "$SYNC" --once >"${WORKDIR}/sync.out"
cmp -s "${caddy}/mqtt.freedriver.io.crt" "${SECRETS}/server.crt"
cmp -s "${WORKDIR}/internal.crt.keep" "${SECRETS}/internal.crt"
cmp -s "${WORKDIR}/internal.key.keep" "${SECRETS}/internal.key"
if openssl verify -CAfile "${CA_DIR}/ca.crt" "${SECRETS}/server.crt" >/dev/null 2>&1; then
  echo "synced public leaf must not verify against the internal CA" >&2
  exit 1
fi

# Hostname-verified TLS. Prints the DNS SAN list on success.
probe() {
  local hostport="$1"
  local hostname="$2"
  local ca="$3"
  local host="${hostport%:*}"
  local port="${hostport##*:}"
  python3 - "$host" "$port" "$ca" "$hostname" <<'PY'
import socket, ssl, sys
host, port, ca, hostname = sys.argv[1:5]
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
try:
    ctx.load_verify_locations(ca)
except OSError as exc:
    print(f"cannot read trust anchor: {exc}", file=sys.stderr)
    sys.exit(1)
ctx.check_hostname = True
ctx.verify_mode = ssl.CERT_REQUIRED
raw = socket.create_connection((host, int(port)), timeout=3)
try:
    ss = ctx.wrap_socket(raw, server_hostname=hostname)
except ssl.SSLError:
    sys.exit(1)
names = [value for kind, value in ss.getpeercert().get("subjectAltName", ()) if kind == "DNS"]
print(",".join(names))
ss.close()
PY
}

python3 - "${SECRETS}/internal.crt" "${SECRETS}/internal.key" \
  "${SECRETS}/server.crt" "${SECRETS}/server.key" "${WORKDIR}/ports" \
  >"${WORKDIR}/tls-server.log" 2>&1 <<'PY' &
import socket, ssl, sys, threading
from pathlib import Path
internal_crt, internal_key, public_crt, public_key, portfile = sys.argv[1:6]

def serve(cert, key):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", 0))
    sock.listen(32)
    port = sock.getsockname()[1]

    def loop():
        while True:
            try:
                conn, _ = sock.accept()
            except OSError:
                return
            try:
                ss = ctx.wrap_socket(conn, server_side=True)
                ss.recv(16)
                ss.close()
            except Exception:
                try:
                    conn.close()
                except Exception:
                    pass

    threading.Thread(target=loop, daemon=True).start()
    return port

internal_port = serve(internal_crt, internal_key)
public_port = serve(public_crt, public_key)
Path(portfile).write_text(f"{internal_port} {public_port}\n")
threading.Event().wait()
PY
tls_server_pid=$!
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if [[ -s "${WORKDIR}/ports" ]]; then
    break
  fi
  sleep 0.1
done
if [[ ! -s "${WORKDIR}/ports" ]]; then
  echo "TLS fixture server did not start" >&2
  cat "${WORKDIR}/tls-server.log" >&2 || true
  exit 1
fi
read -r internal_port public_port < "${WORKDIR}/ports"

internal_live="$(probe "127.0.0.1:${internal_port}" mosquitto "${CA_DIR}/ca.crt")"
if [[ "$internal_live" != "mosquitto" ]]; then
  echo "internal handshake SAN must be mosquitto only, got ${internal_live}" >&2
  exit 1
fi
if probe "127.0.0.1:${internal_port}" mqtt.freedriver.io "${CA_DIR}/ca.crt" >/dev/null; then
  echo "internal socket must reject a name other than mosquitto" >&2
  exit 1
fi
if probe "127.0.0.1:${internal_port}" mosquitto "${SECRETS}/server.crt" >/dev/null; then
  echo "internal socket must not verify against the public leaf" >&2
  exit 1
fi

public_live="$(probe "127.0.0.1:${public_port}" mqtt.freedriver.io "${SECRETS}/server.crt")"
if [[ "$public_live" != "mqtt.freedriver.io" ]]; then
  echo "public handshake SAN must be mqtt.freedriver.io only, got ${public_live}" >&2
  exit 1
fi
if probe "127.0.0.1:${public_port}" mosquitto "${SECRETS}/server.crt" >/dev/null; then
  echo "public socket must reject mosquitto" >&2
  exit 1
fi
if probe "127.0.0.1:${public_port}" mqtt.freedriver.io "${CA_DIR}/ca.crt" >/dev/null; then
  echo "public socket must not verify against the internal CA" >&2
  exit 1
fi
echo "tls handshake: mosquitto verifies with ca.crt; public name does not"

run_broker_handshake() {
  local image="eclipse-mosquitto:2.1.2-alpine"
  docker_net="fd218-mqtt-$$"
  docker_name="fd218-mqtt-$$"
  docker network create "$docker_net" >/dev/null
  local pub_port=18484
  docker run -d --name "$docker_name" --network "$docker_net" --network-alias mosquitto \
    -p "127.0.0.1:${pub_port}:8884" \
    -v "${ROOT}/mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" \
    -v "${SECRETS}:/mosquitto/config/secrets:ro" \
    -v "${WORKDIR}/data:/mosquitto/data" \
    "$image" >/dev/null
  local ready=0
  local logs=""
  for _ in $(seq 1 40); do
    logs="$(docker logs "$docker_name" 2>&1 || true)"
    if grep -q "listen socket on port 8883" <<<"$logs" && grep -q "listen socket on port 8884" <<<"$logs"; then
      ready=1
      break
    fi
    sleep 0.5
  done
  if [[ "$ready" -ne 1 ]]; then
    printf '%s\n' "$logs" >&2
    echo "mosquitto did not open 8883 and 8884" >&2
    exit 1
  fi
  local ip
  ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$docker_name")"
  if [[ -z "$ip" ]]; then
    echo "mosquitto container has no network address" >&2
    exit 1
  fi
  # Container 8883 is not published. Reach it on the docker network.
  probe "${ip}:8883" mosquitto "${CA_DIR}/ca.crt"
  if probe "${ip}:8883" mqtt.freedriver.io "${CA_DIR}/ca.crt"; then
    echo "broker 8883 must reject a name other than mosquitto" >&2
    exit 1
  fi
  broker_internal="$(probe "${ip}:8883" mosquitto "${CA_DIR}/ca.crt")"
  if [[ "$broker_internal" != "mosquitto" ]]; then
    echo "broker 8883 SAN must be mosquitto only, got ${broker_internal}" >&2
    exit 1
  fi
  # server.crt in the secrets dir is owned by 1883 after the fixture
  # chown. The host probe reads this copy, taken before that chown.
  probe "127.0.0.1:${pub_port}" mqtt.freedriver.io "${WORKDIR}/server.crt.probe" >/dev/null
  if probe "127.0.0.1:${pub_port}" mqtt.freedriver.io "${CA_DIR}/ca.crt" >/dev/null; then
    echo "published host port must not verify against the internal CA" >&2
    exit 1
  fi
  broker_public="$(probe "127.0.0.1:${pub_port}" mqtt.freedriver.io "${WORKDIR}/server.crt.probe")"
  if [[ "$broker_public" != "mqtt.freedriver.io" ]]; then
    echo "published port SAN must be mqtt.freedriver.io only, got ${broker_public}" >&2
    exit 1
  fi

  local anon
  anon="$(docker run --rm --network "$docker_net" \
    -v "${CA_DIR}/ca.crt:/ca.crt:ro" \
    "$image" \
    mosquitto_sub -h mosquitto -p 8883 -t probe --cafile /ca.crt -W 2 2>&1 || true)"
  if ! grep -qiE 'not authori[sz]ed' <<<"$anon"; then
    printf '%s\n' "$anon" >&2
    echo "anonymous connect on mosquitto:8883 must be refused" >&2
    exit 1
  fi
  local anon_public
  anon_public="$(docker run --rm --network "$docker_net" \
    -v "${SECRETS}/server.crt:/server.crt:ro" \
    "$image" \
    mosquitto_sub -h mosquitto -p 8884 -t probe --cafile /server.crt --insecure -W 2 2>&1 || true)"
  if ! grep -qiE 'not authori[sz]ed' <<<"$anon_public"; then
    printf '%s\n' "$anon_public" >&2
    echo "anonymous connect on the public listener must be refused" >&2
    exit 1
  fi
  echo "mosquitto handshake: unpublished 8883 is DNS:mosquitto; published 8884 is DNS:mqtt.freedriver.io; anonymous refused"
}

mkdir -p "${WORKDIR}/data"
chmod 777 "${WORKDIR}/data" "$SECRETS"
# The image starts as root. Mosquitto then setuid()s to uid 1883, so a
# root-owned password file and a runner-owned 0600 leaf key are unreadable.
# The host cannot chown them. A root container can, matching production
# (leaf key mode 0600, owner 1883). This does not change the issuer.
if docker info >/dev/null 2>&1; then
  docker pull eclipse-mosquitto:2.1.2-alpine >/dev/null
  # Password file so allow_anonymous false has a password source. The
  # password is a fixture and is not printed.
  docker run --rm \
    -v "${SECRETS}:/mosquitto/config/secrets" \
    eclipse-mosquitto:2.1.2-alpine \
    mosquitto_passwd -c -b /mosquitto/config/secrets/passwd api fixture-not-printed >/dev/null
  printf '%s\n' 'user api' 'topic read probe' > "${SECRETS}/acl"
  # Host-side probes cannot read a 0640 file owned by 1883. Keep a copy
  # of the public certificate for those probes. The broker still reads
  # the secrets-dir file.
  cp "${SECRETS}/server.crt" "${WORKDIR}/server.crt.probe"
  chmod 644 "${WORKDIR}/server.crt.probe"
  docker run --rm --user 0 --entrypoint /bin/sh \
    -v "${SECRETS}:/mosquitto/config/secrets" \
    eclipse-mosquitto:2.1.2-alpine \
    -c 'chown 1883:1883 \
      /mosquitto/config/secrets/passwd \
      /mosquitto/config/secrets/acl \
      /mosquitto/config/secrets/server.crt \
      /mosquitto/config/secrets/server.key \
      /mosquitto/config/secrets/internal.crt \
      /mosquitto/config/secrets/internal.key \
      && chmod 0600 \
      /mosquitto/config/secrets/passwd \
      /mosquitto/config/secrets/server.key \
      /mosquitto/config/secrets/internal.key \
      && chmod 0640 \
      /mosquitto/config/secrets/acl \
      /mosquitto/config/secrets/server.crt \
      /mosquitto/config/secrets/internal.crt'
  run_broker_handshake
elif [[ "${CI:-}" == "true" ]]; then
  echo "docker is required in CI for the Mosquitto handshake" >&2
  exit 1
else
  echo "docker not available; skipped the Mosquitto process handshake"
fi

echo "mqtt two-socket fixture ok"
