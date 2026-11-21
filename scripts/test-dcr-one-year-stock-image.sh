#!/usr/bin/env bash
# Throwaway stock Keycloak 26.7.5 with no provider jar.
# Provision stops because the registration provider freedriver-one-year-tokens is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER=freedriver-dcr-stock-kc
PORT=18083
IMAGE=quay.io/keycloak/keycloak:26.7.5
FIXTURE=scripts/fixtures/keycloak-26.7.5-client-registration-providers.json

if ! command -v docker >/dev/null; then
  echo "docker is required for the stock-image provider check" >&2
  exit 1
fi
if [[ "${KEYCLOAK_CONTAINER:-}" == "freedriver-web-keycloak-1" || "$CONTAINER" == "freedriver-web-keycloak-1" ]]; then
  echo "refusing to run the stock-image check against the production Keycloak container" >&2
  exit 1
fi
unset FREEDRIVER_DEVICE_CLIENTS

work="$(mktemp -d)"
chmod 700 "$work"
cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" --memory=2g --memory-swap=2g \
  -p "127.0.0.1:${PORT}:8080" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e JAVA_OPTS_APPEND="-Xms256m -Xmx768m" \
  "$IMAGE" start-dev >/dev/null

echo "waiting for stock Keycloak"
ready=0
for _ in $(seq 1 90); do
  if docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
      --config /tmp/kcadm-admin.config \
      --server http://127.0.0.1:8080 --realm master --user admin --password admin \
      >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" != 1 ]]; then
  echo "stock Keycloak did not become ready" >&2
  docker logs "$CONTAINER" 2>&1 | tail -n 40 >&2 || true
  exit 1
fi

admin() {
  local verb="$1"
  shift
  docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$verb" \
    --config /tmp/kcadm-admin.config "$@"
}

admin_stdin() {
  local verb="$1"
  shift
  docker exec -i "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$verb" \
    --config /tmp/kcadm-admin.config "$@"
}

admin get client-registration-policy/providers -r master > "$work/live-providers.json"
python3 - "$FIXTURE" "$work/live-providers.json" <<'PY'
import json, sys

def norm(data):
    if isinstance(data, list):
        return [norm(item) for item in data]
    if isinstance(data, dict):
        out = {}
        for key, value in data.items():
            if key == "options" and isinstance(value, list):
                out[key] = sorted(norm(item) for item in value)
            else:
                out[key] = norm(value)
        return out
    return data

fixture = json.load(open(sys.argv[1], encoding="utf-8"))
live = json.load(open(sys.argv[2], encoding="utf-8"))
if norm(live) != norm(fixture):
    def ids(data):
        if not isinstance(data, list):
            return data
        return [item.get("id") if isinstance(item, dict) else item for item in data]
    sys.exit("live provider list differs from the 26.7.5 fixture: " + str(ids(live)))
ids = [item["id"] for item in live]
if "freedriver-one-year-tokens" in ids:
    sys.exit("stock image lists freedriver-one-year-tokens")
print("stock provider list matches the Keycloak 26.7.5 fixture")
PY

openssl rand -hex 24 > "$work/techops.pass"
chmod 600 "$work/techops.pass"
admin create users -r master -s username=techops -s enabled=true >/dev/null
TECHOPS_PASS="$(cat "$work/techops.pass")"
export TECHOPS_PASS
docker exec -e TECHOPS_PASS "$CONTAINER" \
  sh -c '/opt/keycloak/bin/kcadm.sh set-password --config /tmp/kcadm-admin.config -r master --username techops --new-password "$TECHOPS_PASS"' \
  >/dev/null
unset TECHOPS_PASS
admin add-roles -r master --uusername techops --rolename admin >/dev/null

admin create realms -s realm=freedriver -s enabled=true >/dev/null
admin create groups -r freedriver -s name=phone-sign-in >/dev/null
admin get users/profile -r freedriver > "$work/profile.json"
python3 - "$work/profile.json" <<'PY'
import json, sys
path = sys.argv[1]
profile = json.load(open(path, encoding="utf-8"))
attributes = profile.setdefault("attributes", [])
wanted = {"view": ["admin"], "edit": ["admin"]}
for attribute in attributes:
    if attribute.get("name") == "phone":
        attribute["permissions"] = wanted
        break
else:
    attributes.append({
        "name": "phone",
        "displayName": "Phone",
        "permissions": wanted,
        "multivalued": False,
    })
json.dump(profile, open(path, "w", encoding="utf-8"))
PY
admin_stdin update users/profile -r freedriver -f - < "$work/profile.json" >/dev/null

printf 'FREEDRIVER_SEED_PHONE=+15555550143\n' > "$work/secrets.env"
chmod 600 "$work/secrets.env"
mkdir -p "$work/secrets/mqtt-oauth.d"
chmod 700 "$work/secrets"
: > "$work/device-clients.env"

unset FREEDRIVER_DEVICE_CLIENTS
set +e
PROVISION_ALLOW_NON_ROOT=1 \
  KEYCLOAK_CONTAINER="$CONTAINER" \
  TECHOPS_PASS_FILE="$work/techops.pass" \
  SECRET_FILE="$work/secrets/api.secret" \
  INVITE_ENV_FILE="$work/secrets/invite.env" \
  INSTANCES_ENV_FILE="$work/secrets/instances.env" \
  PORTAL_JOBS_ENV_FILE="$work/secrets/portal-jobs.env" \
  GRAFANA_ENV_FILE="$work/secrets/grafana.env" \
  MQTT_ENV_FILE="$work/secrets/mqtt.env" \
  MQTT_OAUTH_DIR="$work/secrets/mqtt-oauth.d" \
  AUTONOMY_MCP_ENV_FILE="$work/secrets/autonomy-mcp.env" \
  OPENBOT_MCP_SECRET_FILE="$work/secrets/openbot.secret" \
  SECRETS_ENV="$work/secrets.env" \
  DEVICE_CLIENTS_FILE="$work/device-clients.env" \
  bash scripts/provision-keycloak-freedriver.sh >"$work/provision.out" 2>"$work/provision.err"
rc=$?
set -e

missing='The Keycloak server is missing the client registration provider freedriver-one-year-tokens.'
if [[ "$rc" -eq 0 ]]; then
  echo "stock image provision exited 0" >&2
  cat "$work/provision.err" >&2
  exit 1
fi
if ! grep -Fqx "$missing" "$work/provision.err"; then
  echo "stock image stderr lacked the missing-provider sentence (exit ${rc})" >&2
  cat "$work/provision.err" >&2
  exit 1
fi
if grep -F 'Could not list client registration providers' "$work/provision.err" \
    || grep -F 'Unreadable client registration provider list' "$work/provision.err"; then
  echo "stock image reported an admin-session or unreadable list" >&2
  cat "$work/provision.err" >&2
  exit 1
fi
if grep -F 'Created anonymous DCR policy' "$work/provision.out" "$work/provision.err"; then
  echo "stock image created the anonymous DCR policy" >&2
  exit 1
fi
echo "stock Keycloak 26.7.5 exits on the missing provider freedriver-one-year-tokens"
