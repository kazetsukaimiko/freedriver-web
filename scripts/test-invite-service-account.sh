#!/usr/bin/env bash
# Throwaway Keycloak 26.7.5 realm in container freedriver-invite-kc-test.
# Expects 403 for role grants, user create, member password and profile edits,
# impersonation, and client reads, and 204 when adding phone-sign-in membership.
# Prints status codes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER=freedriver-invite-kc-test
PORT=18080
BASE="http://127.0.0.1:${PORT}"
HOST_CONTAINER=freedriver-instances-host-kc
HOST_PORT=18081
HOST_BASE="http://127.0.0.1:${HOST_PORT}"
IMAGE=quay.io/keycloak/keycloak:26.7.5

if ! command -v docker >/dev/null; then
  echo "docker is required for the invite service-account checks" >&2
  exit 1
fi
if [[ "${KEYCLOAK_CONTAINER:-}" == "freedriver-web-keycloak-1" || "$CONTAINER" == "freedriver-web-keycloak-1" || "$HOST_CONTAINER" == "freedriver-web-keycloak-1" ]]; then
  echo "refusing to run the invite checks against the production Keycloak container" >&2
  exit 1
fi
unset FREEDRIVER_DEVICE_CLIENTS

work="$(mktemp -d)"
chmod 700 "$work"
cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$HOST_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

echo "building the SMS OTP provider jar"
./mvnw -B -C -pl sms-client -am package -DskipTests
./mvnw -B -C -pl sms-client -am install -DskipTests
./mvnw -B -f keycloak/sms-otp-spi/pom.xml -DskipTests package
PROVIDER_JAR="$ROOT/keycloak/sms-otp-spi/target/freedriver-sms-otp-spi-1.0.0-SNAPSHOT.jar"
if [[ ! -s "$PROVIDER_JAR" ]]; then
  echo "missing provider jar $PROVIDER_JAR" >&2
  exit 1
fi

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" --memory=2g --memory-swap=2g \
  -p "127.0.0.1:${PORT}:8080" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e JAVA_OPTS_APPEND="-Xms256m -Xmx768m" \
  -v "${PROVIDER_JAR}:/opt/keycloak/providers/freedriver-sms-otp.jar:ro" \
  "$IMAGE" start-dev >/dev/null

echo "waiting for throwaway Keycloak"
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
  echo "throwaway Keycloak did not become ready" >&2
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

csv_id() {
  sed '/^id$/d' | tr -d '\r' | awk 'NF { print; exit }'
}

client_attributes() {   # <client uuid> <out file>
  admin get "clients/$1" -r freedriver \
    | python3 -c 'import json,sys; json.dump({"attributes": json.load(sys.stdin).get("attributes") or {}}, sys.stdout)' > "$2"
}

# Group permission scopes after a repair run are manage-membership only.
assert_invite_scopes() {
  local admin_client perm_id perm_name
  admin_client="$(admin get clients -r freedriver -q clientId=admin-permissions --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${admin_client}/authz/resource-server/permission/scope" -r freedriver > "$work/perms.json"
  python3 - "$work/perms.json" "$work/perm-ids" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("permissions") or data.get("results") or []
wanted = (
    "invite-phone-sign-in-membership",
    "invite-user-group-membership",
)
found = {}
for item in data:
    if isinstance(item, dict) and item.get("name") in wanted and item.get("id"):
        found[item["name"]] = item["id"]
missing = [name for name in wanted if name not in found]
if missing:
    sys.exit("missing permissions: " + ", ".join(missing))
lines = [found[name] + "\t" + name for name in wanted]
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
  while IFS=$'\t' read -r perm_id perm_name; do
    admin get "clients/${admin_client}/authz/resource-server/permission/scope/${perm_id}?fields=*" -r freedriver > "$work/perm-one.json"
    python3 - "$work/perm-one.json" "$perm_name" <<'PY' || exit 1
import json, re, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
uuid = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
scopes = []
for entry in list(item.get("scopes") or []) + list(item.get("scopesData") or []):
    if isinstance(entry, str):
        if uuid.fullmatch(entry):
            continue
        scopes.append(entry)
    elif isinstance(entry, dict) and entry.get("name"):
        scopes.append(entry["name"])
got = sorted(set(scopes))
want = {
    "invite-phone-sign-in-membership": ["manage-membership"],
    "invite-user-group-membership": ["manage-group-membership"],
}[sys.argv[2]]
if got != sorted(want):
    sys.exit(f"{sys.argv[2]} scopes {got}, want {sorted(want)}")
print(sys.argv[2], "scopes", got)
PY
  done < "$work/perm-ids"
}

# Add manage-members to the group permission so the next provision replaces that scope set.
widen_group_permission() {
  local admin_client perm_id
  admin_client="$(admin get clients -r freedriver -q clientId=admin-permissions --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${admin_client}/authz/resource-server/permission/scope" -r freedriver > "$work/perms.json"
  perm_id="$(python3 - "$work/perms.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("permissions") or data.get("results") or []
for item in data:
    if isinstance(item, dict) and item.get("name") == "invite-phone-sign-in-membership" and item.get("id"):
        print(item["id"])
        break
PY
)"
  perm_id="$(printf '%s' "$perm_id" | tr -d '\r')"
  if [[ -z "$perm_id" ]]; then
    echo "invite group permission is missing before the widen step" >&2
    exit 1
  fi
  admin get "clients/${admin_client}/authz/resource-server/permission/scope/${perm_id}?fields=*" -r freedriver > "$work/perm-one.json"
  python3 - "$work/perm-one.json" > "$work/wide.json" <<'PY'
import json, re, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
uuid = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
names = []
for entry in list(item.get("scopes") or []) + list(item.get("scopesData") or []):
    if isinstance(entry, str):
        if uuid.fullmatch(entry):
            continue
        names.append(entry)
    elif isinstance(entry, dict) and entry.get("name"):
        names.append(entry["name"])
for extra in ("view", "view-members", "manage-members"):
    if extra not in names:
        names.append(extra)
resources = []
for entry in list(item.get("resources") or []) + list(item.get("resourcesData") or []):
    if isinstance(entry, str):
        resources.append(entry)
    elif isinstance(entry, dict):
        resources.append(entry.get("_id") or entry.get("id") or entry.get("name"))
resources = [resource for resource in resources if resource]
policies = item.get("policies") or ["freedriver-invite-sa"]
if policies and isinstance(policies[0], dict):
    policies = [entry.get("name") or entry.get("id") for entry in policies if entry.get("name") or entry.get("id")]
json.dump({
    "id": item.get("id"),
    "name": item["name"],
    "type": "scope",
    "logic": item.get("logic") or "POSITIVE",
    "decisionStrategy": item.get("decisionStrategy") or "UNANIMOUS",
    "resourceType": item.get("resourceType") or "Groups",
    "resources": resources,
    "scopes": names,
    "policies": policies,
}, sys.stdout)
PY
  admin_stdin update "clients/${admin_client}/authz/resource-server/permission/scope/${perm_id}" -r freedriver -f - < "$work/wide.json" >/dev/null
  admin get "clients/${admin_client}/authz/resource-server/permission/scope/${perm_id}?fields=*" -r freedriver > "$work/perm-one.json"
  python3 - "$work/perm-one.json" <<'PY'
import json, re, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
uuid = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
names = []
for entry in list(item.get("scopes") or []) + list(item.get("scopesData") or []):
    if isinstance(entry, str):
        if uuid.fullmatch(entry):
            continue
        names.append(entry)
    elif isinstance(entry, dict) and entry.get("name"):
        names.append(entry["name"])
if "manage-members" not in names:
    sys.exit("widen step left manage-members off the group permission: " + " ".join(sorted(set(names))))
print("widened invite group permission", sorted(set(names)))
PY
}

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
admin create groups -r freedriver -s name=other-group >/dev/null
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
mkdir -p "$work/secrets"
chmod 700 "$work/secrets"

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
  SECRETS_ENV="$work/secrets.env" \
  bash scripts/provision-keycloak-freedriver.sh

if ! grep -q '^FREEDRIVER_INVITE_CLIENT_SECRET=.\+' "$work/secrets/invite.env"; then
  echo "invite env file is missing a secret value" >&2
  exit 1
fi
if grep -q 'FREEDRIVER_INVITE_CLIENT_SECRET' "$work/secrets/api.secret" "$work/secrets.env"; then
  echo "invite secret leaked into another file" >&2
  exit 1
fi
if ! grep -q '^FREEDRIVER_INSTANCES_CLIENT_SECRET=.\+' "$work/secrets/instances.env"; then
  echo "instances env file is missing a secret value" >&2
  exit 1
fi
if ! grep -q '^FREEDRIVER_PORTAL_JOBS_CLIENT_SECRET=.\+' "$work/secrets/portal-jobs.env"; then
  echo "portal jobs env file is missing a secret value" >&2
  exit 1
fi
if grep -q 'FREEDRIVER_INSTANCES_CLIENT_SECRET' "$work/secrets/api.secret" "$work/secrets.env" "$work/secrets/invite.env"; then
  echo "instances secret leaked into another file" >&2
  exit 1
fi
if ! grep -q '^GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET=.\+' "$work/secrets/grafana.env"; then
  echo "grafana env file is missing a secret value" >&2
  exit 1
fi
if grep -q 'GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET\|FREEDRIVER_GRAFANA_CLIENT_SECRET' "$work/secrets/api.secret" "$work/secrets.env" "$work/secrets/invite.env" "$work/secrets/instances.env"; then
  echo "grafana secret leaked into another file" >&2
  exit 1
fi
if ! grep -q '^FREEDRIVER_MQTT_CLIENT_SECRET=.\+' "$work/secrets/mqtt.env"; then
  echo "mqtt env file is missing a secret value" >&2
  exit 1
fi
if ! grep -q '^FREEDRIVER_MQTT_SA_PASSWORD=.\+' "$work/secrets/mqtt.env"; then
  echo "mqtt env file is missing a service-account password" >&2
  exit 1
fi
if grep -q 'FREEDRIVER_MQTT_CLIENT_SECRET\|FREEDRIVER_MQTT_SA_PASSWORD' "$work/secrets/api.secret" "$work/secrets.env" "$work/secrets/invite.env" "$work/secrets/instances.env" "$work/secrets/grafana.env"; then
  echo "mqtt secret leaked into another file" >&2
  exit 1
fi
if [[ ! -s "$work/secrets/mqtt-oauth.d/plugin.conf" ]]; then
  echo "mqtt plugin options file is missing" >&2
  exit 1
fi
if ! grep -q '^FREEDRIVER_AUTONOMY_MCP_CLIENT_SECRET=.\+' "$work/secrets/autonomy-mcp.env"; then
  echo "autonomy MCP env file is missing a secret value" >&2
  exit 1
fi

assert_offline_access() {
  local openbot_uuid grok_uuid api_uuid dispatcher_uuid dcr_uuid
  openbot_uuid="$(admin get clients -r freedriver -q clientId=freedriver-openbot --fields id --format csv --noquotes | csv_id)"
  grok_uuid="$(admin get clients -r freedriver -q clientId=freedriver-grok-mcp --fields id --format csv --noquotes | csv_id)"
  api_uuid="$(admin get clients -r freedriver -q clientId=freedriver-api --fields id --format csv --noquotes | csv_id)"
  dispatcher_uuid="$(admin get clients -r freedriver -q clientId=freedriver-dispatcher --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${openbot_uuid}/default-client-scopes" -r freedriver > "$work/openbot-scopes.json"
  admin get "clients/${grok_uuid}/default-client-scopes" -r freedriver > "$work/grok-scopes.json"
  admin get "clients/${api_uuid}/default-client-scopes" -r freedriver > "$work/api-scopes.json"
  admin get "clients/${dispatcher_uuid}/default-client-scopes" -r freedriver > "$work/dispatcher-scopes.json"
  admin get default-default-client-scopes -r freedriver > "$work/realm-default-scopes.json"
  admin get "roles/default-roles-freedriver/composites" -r freedriver > "$work/default-role-composites.json"
  client_attributes "$openbot_uuid" "$work/openbot-attrs.json"
  client_attributes "$grok_uuid" "$work/grok-attrs.json"
  client_attributes "$dispatcher_uuid" "$work/dispatcher-attrs.json"
  client_attributes "$api_uuid" "$work/api-attrs.json"
  admin get realms/freedriver \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); json.dump({"offlineSessionMaxLifespanEnabled": d.get("offlineSessionMaxLifespanEnabled"), "offlineSessionIdleTimeout": d.get("offlineSessionIdleTimeout")}, sys.stdout)' \
    > "$work/realm-offline-max.json"
  python3 - "$work/openbot-scopes.json" "$work/grok-scopes.json" "$work/api-scopes.json" \
    "$work/realm-default-scopes.json" "$work/default-role-composites.json" "$work/openbot-attrs.json" \
    "$work/dispatcher-scopes.json" "$work/grok-attrs.json" "$work/dispatcher-attrs.json" \
    "$work/api-attrs.json" "$work/realm-offline-max.json" <<'PY'
import json, sys

def names(path):
    data = json.load(open(path, encoding="utf-8"))
    return {item.get("name") for item in data if item.get("name")}

def lifespan(path):
    return (json.load(open(path, encoding="utf-8")).get("attributes") or {}).get("access.token.lifespan")

openbot, grok, api, realm, roles = (names(p) for p in sys.argv[1:6])
dispatcher = names(sys.argv[7])
openbot_life = lifespan(sys.argv[6])
grok_life = lifespan(sys.argv[8])
dispatcher_life = lifespan(sys.argv[9])
api_life = lifespan(sys.argv[10])
realm_offline = json.load(open(sys.argv[11], encoding="utf-8"))
if "offline_access" not in openbot:
    sys.exit("freedriver-openbot default scopes lack offline_access: " + ",".join(sorted(openbot)))
if "offline_access" not in grok:
    sys.exit("freedriver-grok-mcp default scopes lack offline_access: " + ",".join(sorted(grok)))
if "offline_access" not in dispatcher:
    sys.exit("freedriver-dispatcher default scopes lack offline_access: " + ",".join(sorted(dispatcher)))
if "offline_access" in api:
    sys.exit("freedriver-api must not default offline_access")
if "offline_access" in realm:
    sys.exit("offline_access must not be a realm default client scope")
if "offline_access" not in roles:
    sys.exit("default-roles-freedriver must include offline_access: " + ",".join(sorted(roles)))
if openbot_life != "31536000":
    sys.exit("freedriver-openbot access.token.lifespan is %s" % openbot_life)
if grok_life != "31536000":
    sys.exit("freedriver-grok-mcp access.token.lifespan is %s" % grok_life)
if dispatcher_life != "31536000":
    sys.exit("freedriver-dispatcher access.token.lifespan is %s" % dispatcher_life)
if api_life:
    sys.exit("freedriver-api has access.token.lifespan")
if realm_offline.get("offlineSessionMaxLifespanEnabled") is not False:
    sys.exit("offlineSessionMaxLifespanEnabled is %s" % realm_offline.get("offlineSessionMaxLifespanEnabled"))
if realm_offline.get("offlineSessionIdleTimeout") != 34560000:
    sys.exit("offlineSessionIdleTimeout is %s" % realm_offline.get("offlineSessionIdleTimeout"))
print("openbot, grok-mcp, and dispatcher default scopes include offline_access")
print("freedriver-api does not default offline_access")
print("realm default-default omits offline_access")
print("default-roles-freedriver includes offline_access")
print("openbot, grok-mcp, and dispatcher access.token.lifespan is 31536000")
print("offlineSessionMaxLifespanEnabled is false")
print("offlineSessionIdleTimeout is 34560000")
PY

  admin create clients -r freedriver \
    -s clientId=dcr-offline-proof \
    -s publicClient=true \
    -s standardFlowEnabled=true \
    -s directAccessGrantsEnabled=true \
    -s consentRequired=false \
    -s protocol=openid-connect \
    -s 'redirectUris=["https://grok.com/connectors-oauth-exchange-code/"]' >/dev/null
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
    SECRETS_ENV="$work/secrets.env" \
    bash scripts/provision-keycloak-freedriver.sh >/dev/null
  dcr_uuid="$(admin get clients -r freedriver -q clientId=dcr-offline-proof --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${dcr_uuid}/default-client-scopes" -r freedriver > "$work/dcr-scopes.json"
  client_attributes "$dcr_uuid" "$work/dcr-attrs.json"
  python3 - "$work/dcr-scopes.json" "$work/dcr-attrs.json" <<'PY'
import json, sys
names = {item.get("name") for item in json.load(open(sys.argv[1], encoding="utf-8")) if item.get("name")}
attrs = json.load(open(sys.argv[2], encoding="utf-8")).get("attributes") or {}
if "offline_access" not in names:
    sys.exit("grok.com DCR client default scopes lack offline_access")
if attrs.get("access.token.lifespan") != "31536000":
    sys.exit("grok.com DCR client access.token.lifespan is %s" % attrs.get("access.token.lifespan"))
print("grok.com DCR client default scopes include offline_access")
print("grok.com DCR client access.token.lifespan is 31536000")
PY

  admin create users -r freedriver \
    -s username=offline-proof \
    -s enabled=true \
    -s email=offline-proof@freedriver.invalid \
    -s emailVerified=true \
    -s firstName=Offline \
    -s lastName=Proof >/dev/null
  openssl rand -hex 16 > "$work/offline-proof.pass"
  chmod 600 "$work/offline-proof.pass"
  OFFLINE_PASS="$(cat "$work/offline-proof.pass")"
  export OFFLINE_PASS
  docker exec -e OFFLINE_PASS "$CONTAINER" \
    sh -c '/opt/keycloak/bin/kcadm.sh set-password --config /tmp/kcadm-admin.config -r freedriver --username offline-proof --new-password "$OFFLINE_PASS"' \
    >/dev/null
  admin get "clients/${openbot_uuid}/client-secret" -r freedriver --fields value --format csv --noquotes \
    | sed '/^value$/d' | tr -d '\r' | awk 'NF { print; exit }' > "$work/openbot.secret"
  chmod 600 "$work/openbot.secret"
  python3 - "$work/openbot.secret" "$work/openbot-cc.form" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlencode
secret = Path(sys.argv[1]).read_text(encoding="utf-8").strip()
Path(sys.argv[2]).write_text(urlencode({
    "grant_type": "client_credentials",
    "client_id": "freedriver-openbot",
    "client_secret": secret,
}), encoding="utf-8")
PY
  python3 - "$work/offline-proof.pass" "$work/dcr-ropc.form" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlencode
password = Path(sys.argv[1]).read_text(encoding="utf-8").strip()
Path(sys.argv[2]).write_text(urlencode({
    "grant_type": "password",
    "client_id": "dcr-offline-proof",
    "username": "offline-proof",
    "password": password,
    "scope": "openid",
}), encoding="utf-8")
PY
  chmod 600 "$work/dcr-ropc.form" "$work/openbot-cc.form"
  curl -sS --max-time 30 -o "$work/openbot-token-1.json" -w "%{http_code}" \
    -X POST "${BASE}/realms/freedriver/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-binary @"$work/openbot-cc.form" > "$work/openbot-token-1.status"
  curl -sS --max-time 30 -o "$work/openbot-token-2.json" -w "%{http_code}" \
    -X POST "${BASE}/realms/freedriver/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-binary @"$work/openbot-cc.form" > "$work/openbot-token-2.status"
  curl -sS --max-time 30 -o "$work/dcr-token.json" -w "%{http_code}" \
    -X POST "${BASE}/realms/freedriver/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-binary @"$work/dcr-ropc.form" > "$work/dcr-token.status"
  python3 - "$work/dcr-token.json" "$work/dcr-refresh.form" <<'PY'
import json, sys
from pathlib import Path
from urllib.parse import urlencode
dcr = json.load(open(sys.argv[1], encoding="utf-8"))
refresh = dcr.get("refresh_token")
if not isinstance(refresh, str) or not refresh:
    sys.exit("dcr token missing refresh_token")
Path(sys.argv[2]).write_text(urlencode({
    "grant_type": "refresh_token",
    "client_id": "dcr-offline-proof",
    "refresh_token": refresh,
}), encoding="utf-8")
PY
  chmod 600 "$work/dcr-refresh.form"
  curl -sS --max-time 30 -o "$work/dcr-refresh.json" -w "%{http_code}" \
    -X POST "${BASE}/realms/freedriver/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-binary @"$work/dcr-refresh.form" > "$work/dcr-refresh.status"
  python3 - "$work/openbot-token-1.status" "$work/openbot-token-1.json" \
    "$work/openbot-token-2.status" "$work/openbot-token-2.json" \
    "$work/dcr-token.status" "$work/dcr-token.json" \
    "$work/dcr-refresh.status" "$work/dcr-refresh.json" <<'PY'
import json, sys
s1, p1, s2, p2, dcr_status, dcr_path, refresh_status, refresh_path = sys.argv[1:]
code1 = open(s1, encoding="utf-8").read().strip()
code2 = open(s2, encoding="utf-8").read().strip()
dcr_code = open(dcr_status, encoding="utf-8").read().strip()
refresh_code = open(refresh_status, encoding="utf-8").read().strip()
openbot1 = json.load(open(p1, encoding="utf-8"))
openbot2 = json.load(open(p2, encoding="utf-8"))
dcr = json.load(open(dcr_path, encoding="utf-8"))
refreshed = json.load(open(refresh_path, encoding="utf-8"))
if code1 != "200":
    sys.exit("openbot client-credentials HTTP " + code1)
if code2 != "200":
    sys.exit("openbot client-credentials second HTTP " + code2)
if dcr_code != "200":
    sys.exit("dcr password grant HTTP " + dcr_code)
if refresh_code != "200":
    sys.exit("dcr refresh grant HTTP " + refresh_code)
openbot_exp = openbot1.get("expires_in")
dcr_exp = dcr.get("expires_in")
refresh_access_exp = refreshed.get("expires_in")
refresh_exp = dcr.get("refresh_expires_in")
if not (type(openbot_exp) is int and openbot_exp in (31535999, 31536000)):
    sys.exit("openbot expires_in is %r, want 31535999 or 31536000" % (openbot_exp,))
if not (type(dcr_exp) is int and dcr_exp in (31535999, 31536000)):
    sys.exit("dcr expires_in is %r, want 31535999 or 31536000" % (dcr_exp,))
if not (type(refresh_access_exp) is int and refresh_access_exp in (31535999, 31536000)):
    sys.exit("dcr refresh expires_in is %r, want 31535999 or 31536000" % (refresh_access_exp,))
if "refresh_token" not in dcr:
    sys.exit("dcr token missing refresh_token")
if refresh_exp != 0:
    sys.exit("dcr refresh_expires_in is %s, want 0" % refresh_exp)
print("openbot expires_in", openbot_exp)
print("dcr expires_in", dcr_exp)
print("dcr refresh access expires_in", refresh_access_exp)
print("dcr refresh_expires_in", refresh_exp)
print("openbot client-credentials calls 2")
PY
  openbot_sa="$(admin get "clients/${openbot_uuid}/service-account-user" -r freedriver \
    --fields id --format csv --noquotes | csv_id)"
  admin get "users/${openbot_sa}/offline-sessions/${openbot_uuid}" -r freedriver \
    > "$work/openbot-sa-offline-sessions.json"
  admin get "clients/${openbot_uuid}/offline-session-count" -r freedriver \
    > "$work/openbot-offline-session-count.json"
  python3 - "$work/openbot-sa-offline-sessions.json" \
    "$work/openbot-offline-session-count.json" <<'PY'
import json, sys
sessions = json.load(open(sys.argv[1], encoding="utf-8"))
count_body = json.load(open(sys.argv[2], encoding="utf-8"))
if not isinstance(sessions, list):
    sys.exit("openbot service-account offline sessions are not a list")
count = count_body.get("count") if isinstance(count_body, dict) else count_body
if count is None:
    sys.exit("openbot offline-session-count missing count")
print("openbot service-account offline sessions", len(sessions))
print("openbot client offline-session-count", count)
if len(sessions) != 0 or count != 0:
    sys.exit(
        "client-credentials created offline sessions for freedriver-openbot "
        "(service-account %s, client count %s)" % (len(sessions), count)
    )
PY
  unset OFFLINE_PASS
  rm -f "$work/openbot.secret" "$work/openbot-cc.form" "$work/dcr-ropc.form" \
    "$work/openbot-token-1.json" "$work/openbot-token-2.json" "$work/dcr-token.json" \
    "$work/offline-proof.pass" "$work/openbot-token-1.status" \
    "$work/openbot-token-2.status" "$work/dcr-token.status" \
    "$work/dcr-refresh.form" "$work/dcr-refresh.json" "$work/dcr-refresh.status" \
    "$work/openbot-sa-offline-sessions.json" "$work/openbot-offline-session-count.json"
}

# A client grok.com registers after provision has both settings with no
# second provision. A trusted-host redirect that is not that callback has neither.
assert_registration_one_year() {
  local grok_redirect='https://grok.com/connectors-oauth-exchange-code/'
  local other_redirect='https://x.ai/not-the-grok-callback'
  python3 - "$grok_redirect" "$work/reg-grok.json" <<'PY'
import json, sys
json.dump({
    "client_name": "reg-one-year",
    "redirect_uris": [sys.argv[1]],
    "token_endpoint_auth_method": "none",
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
}, open(sys.argv[2], "w", encoding="utf-8"))
PY
  python3 - "$other_redirect" "$work/reg-other.json" <<'PY'
import json, sys
json.dump({
    "client_name": "reg-other-redirect",
    "redirect_uris": [sys.argv[1]],
    "token_endpoint_auth_method": "none",
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
}, open(sys.argv[2], "w", encoding="utf-8"))
PY
  grok_status="$(curl -sS --max-time 30 -o "$work/reg-grok-out.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary @"$work/reg-grok.json" \
    "${BASE}/realms/freedriver/clients-registrations/openid-connect")"
  if [[ "$grok_status" != 201 ]]; then
    echo "grok.com registration HTTP ${grok_status}" >&2
    cat "$work/reg-grok-out.json" >&2
    exit 1
  fi
  other_status="$(curl -sS --max-time 30 -o "$work/reg-other-out.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    --data-binary @"$work/reg-other.json" \
    "${BASE}/realms/freedriver/clients-registrations/openid-connect")"
  if [[ "$other_status" != 201 ]]; then
    echo "other redirect registration HTTP ${other_status}" >&2
    cat "$work/reg-other-out.json" >&2
    exit 1
  fi
  grok_client_id="$(python3 - "$work/reg-grok-out.json" <<'PY'
import json, sys
client_id = json.load(open(sys.argv[1], encoding="utf-8")).get("client_id")
if not client_id:
    raise SystemExit("grok.com registration missing client_id")
print(client_id)
PY
)"
  other_client_id="$(python3 - "$work/reg-other-out.json" <<'PY'
import json, sys
client_id = json.load(open(sys.argv[1], encoding="utf-8")).get("client_id")
if not client_id:
    raise SystemExit("other registration missing client_id")
print(client_id)
PY
)"
  grok_uuid="$(admin get clients -r freedriver -q "clientId=${grok_client_id}" --fields id --format csv --noquotes | csv_id)"
  other_uuid="$(admin get clients -r freedriver -q "clientId=${other_client_id}" --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${grok_uuid}/default-client-scopes" -r freedriver > "$work/reg-grok-scopes.json"
  admin get "clients/${other_uuid}/default-client-scopes" -r freedriver > "$work/reg-other-scopes.json"
  client_attributes "$grok_uuid" "$work/reg-grok-attrs.json"
  client_attributes "$other_uuid" "$work/reg-other-attrs.json"
  python3 - "$work/reg-grok-scopes.json" "$work/reg-grok-attrs.json" \
    "$work/reg-other-scopes.json" "$work/reg-other-attrs.json" <<'PY'
import json, sys
def names(path):
    return {item.get("name") for item in json.load(open(path, encoding="utf-8")) if item.get("name")}
def lifespan(path):
    return (json.load(open(path, encoding="utf-8")).get("attributes") or {}).get("access.token.lifespan")
grok_names, grok_life, other_names, other_life = names(sys.argv[1]), lifespan(sys.argv[2]), names(sys.argv[3]), lifespan(sys.argv[4])
if "offline_access" not in grok_names:
    sys.exit("registered grok.com client default scopes lack offline_access")
if grok_life != "31536000":
    sys.exit("registered grok.com client access.token.lifespan is %s" % grok_life)
if "offline_access" in other_names:
    sys.exit("other redirect default scopes include offline_access")
if other_life is not None:
    sys.exit("other redirect access.token.lifespan is %s" % other_life)
print("registered grok.com client has one-year tokens")
print("registered other redirect has neither one-year setting")
PY

  admin update "clients/${grok_uuid}" -r freedriver \
    -s directAccessGrantsEnabled=true \
    -s consentRequired=false >/dev/null
  admin create users -r freedriver \
    -s username=reg-one-year \
    -s enabled=true \
    -s email=reg-one-year@freedriver.invalid \
    -s emailVerified=true \
    -s firstName=Reg \
    -s lastName=Year >/dev/null
  openssl rand -hex 16 > "$work/reg-one-year.pass"
  chmod 600 "$work/reg-one-year.pass"
  REG_PASS="$(cat "$work/reg-one-year.pass")"
  export REG_PASS
  docker exec -e REG_PASS "$CONTAINER" \
    sh -c '/opt/keycloak/bin/kcadm.sh set-password --config /tmp/kcadm-admin.config -r freedriver --username reg-one-year --new-password "$REG_PASS"' \
    >/dev/null
  python3 - "$work/reg-one-year.pass" "$grok_client_id" "$work/reg-ropc.form" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlencode
password = Path(sys.argv[1]).read_text(encoding="utf-8").strip()
Path(sys.argv[3]).write_text(urlencode({
    "grant_type": "password",
    "client_id": sys.argv[2],
    "username": "reg-one-year",
    "password": password,
    "scope": "openid",
}), encoding="utf-8")
PY
  chmod 600 "$work/reg-ropc.form"
  curl -sS --max-time 30 -o "$work/reg-token.json" -w "%{http_code}" \
    -X POST "${BASE}/realms/freedriver/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-binary @"$work/reg-ropc.form" > "$work/reg-token.status"
  python3 - "$work/reg-token.status" "$work/reg-token.json" <<'PY'
import json, sys
code = open(sys.argv[1], encoding="utf-8").read().strip()
body = json.load(open(sys.argv[2], encoding="utf-8"))
if code != "200":
    sys.exit("registered client password grant HTTP %s %s" % (code, body.get("error") or body))
expires = body.get("expires_in")
if not (type(expires) is int and expires in (31535999, 31536000)):
    sys.exit("registered client expires_in is %r, want 31535999 or 31536000" % (expires,))
print("registered client expires_in", expires)
PY
  unset REG_PASS
  rm -f "$work/reg-grok.json" "$work/reg-other.json" "$work/reg-grok-out.json" \
    "$work/reg-other-out.json" "$work/reg-grok-scopes.json" "$work/reg-other-scopes.json" \
    "$work/reg-grok-attrs.json" "$work/reg-other-attrs.json" "$work/reg-one-year.pass" \
    "$work/reg-ropc.form" "$work/reg-token.json" "$work/reg-token.status"
}

assert_portal_jobs_token() {
  local jobs_uuid mapper_count
  jobs_uuid="$(admin get clients -r freedriver -q clientId=freedriver-portal-jobs --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${jobs_uuid}/protocol-mappers/models" -r freedriver --fields name \
    > "$work/portal-jobs-mappers.json"
  mapper_count="$(python3 - "$work/portal-jobs-mappers.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print(sum(1 for item in data if item.get("name") == "realm roles"))
PY
)"
  python3 - "$work/secrets/portal-jobs.env" "$work/portal-jobs-token-form" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlencode
secret = Path(sys.argv[1]).read_text(encoding="utf-8").split("=", 1)[1].strip()
Path(sys.argv[2]).write_text(urlencode({
    "grant_type": "client_credentials",
    "client_id": "freedriver-portal-jobs",
    "client_secret": secret,
}), encoding="utf-8")
PY
  chmod 600 "$work/portal-jobs-token-form"
  curl -sS -o "$work/portal-jobs-token.json" --data-binary @"$work/portal-jobs-token-form" \
    "${BASE}/realms/freedriver/protocol/openid-connect/token"
  rm -f "$work/portal-jobs-token-form"
  python3 - "$work/portal-jobs-token.json" "$mapper_count" <<'PY'
import base64, json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token")
if not token:
    print(data.get("error") or "no portal-jobs token", file=sys.stderr)
    sys.exit(1)
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
roles = list((claims.get("realm_access") or {}).get("roles") or [])
if roles != ["dispatcher-admin"]:
    sys.exit("portal jobs token roles are %s, want [dispatcher-admin]" % roles)
count = int(sys.argv[2])
if count != 1:
    sys.exit("portal jobs realm roles mapper count is %s, want 1" % count)
print("portal jobs mapper count:", count)
print("portal jobs token roles:", ",".join(roles))
PY
  rm -f "$work/portal-jobs-token.json"
}

# Fresh instances token: realm-management roles are exactly manage-users, and the
# admin group and member reads return 200. The bearer stays in a curl config file.
instances_admin_probe() {
  local base="$1" envfile="$2" dir="$3" expect_iss="$4"
  local tracing=0 group_id token_code groups_code members_code token_rc
  case $- in
    *x*) tracing=1 ;;
  esac
  set +x
  python3 - "$envfile" "$dir/instances-token.form" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlencode
secret = Path(sys.argv[1]).read_text(encoding="utf-8").split("=", 1)[1].strip()
Path(sys.argv[2]).write_text(urlencode({
    "grant_type": "client_credentials",
    "client_id": "freedriver-instances",
    "client_secret": secret,
}), encoding="utf-8")
PY
  chmod 600 "$dir/instances-token.form"
  token_code="$(curl -sS --max-redirs 0 --max-time 30 -o "$dir/instances-token.json" -w '%{http_code}' \
    -X POST "${base}/realms/freedriver/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-binary @"$dir/instances-token.form" || true)"
  rm -f "$dir/instances-token.form"
  echo "instances token HTTP ${token_code}"
  token_rc=0
  python3 - "$dir/instances-token.json" "$token_code" "$dir/instances.curl" "$expect_iss" <<'PY' || token_rc=$?
import base64, json, sys
from pathlib import Path
code = sys.argv[2]
expect = sys.argv[4]
if code != "200":
    sys.exit("instances token status is %s" % code)
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token") or ""
if not token:
    sys.exit("instances token response has no access token")
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
roles = ((claims.get("resource_access") or {}).get("realm-management") or {}).get("roles")
if roles != ["manage-users"]:
    sys.exit("instances token realm-management roles are not exactly manage-users")
if expect and claims.get("iss") != expect:
    sys.exit("instances token iss is %s" % (claims.get("iss") or ""))
Path(sys.argv[3]).write_text('header = "Authorization: Bearer %s"\n' % token, encoding="utf-8")
if expect:
    print("instances token iss:", claims.get("iss"))
print("instances token roles: manage-users")
PY
  if [[ "$token_rc" -ne 0 ]]; then
    rm -f "$dir/instances-token.json" "$dir/instances.curl"
    if [[ "$tracing" == 1 ]]; then set -x; fi
    exit "$token_rc"
  fi
  chmod 600 "$dir/instances.curl"
  groups_code="$(curl -sS --config "$dir/instances.curl" --max-redirs 0 --max-time 30 \
    -o "$dir/instances-groups.json" -w '%{http_code}' \
    "${base}/admin/realms/freedriver/groups?search=mqtt-instance" || true)"
  echo "instances groups HTTP ${groups_code}"
  if [[ "$groups_code" != "200" ]]; then
    rm -f "$dir/instances-token.json" "$dir/instances.curl" "$dir/instances-groups.json"
    if [[ "$tracing" == 1 ]]; then set -x; fi
    echo "instances groups status is ${groups_code}" >&2
    exit 1
  fi
  group_id="$(python3 - "$dir/instances-groups.json" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [g.get("id") for g in groups if g.get("name") == "mqtt-instance" and not g.get("parentId") and g.get("id")]
if len(ids) != 1:
    sys.exit("instances groups response has no single mqtt-instance id")
print(ids[0])
PY
)"
  members_code="$(curl -sS --config "$dir/instances.curl" --max-redirs 0 --max-time 30 \
    -o "$dir/instances-members.json" -w '%{http_code}' \
    "${base}/admin/realms/freedriver/groups/${group_id}/members" || true)"
  echo "instances members HTTP ${members_code}"
  rm -f "$dir/instances-token.json" "$dir/instances.curl" "$dir/instances-groups.json" "$dir/instances-members.json"
  if [[ "$tracing" == 1 ]]; then set -x; fi
  if [[ "$members_code" != "200" ]]; then
    echo "instances members status is ${members_code}" >&2
    exit 1
  fi
}

assert_instances_token() {
  instances_admin_probe "$BASE" "$work/secrets/instances.env" "$work" ""
}

assert_portal_jobs_token
assert_instances_token
assert_offline_access
assert_registration_one_year
if grep -q 'FREEDRIVER_AUTONOMY_MCP_CLIENT_SECRET' "$work/secrets/api.secret" "$work/secrets.env" "$work/secrets/invite.env" "$work/secrets/instances.env" "$work/secrets/grafana.env" "$work/secrets/mqtt.env"; then
  echo "autonomy MCP secret leaked into another file" >&2
  exit 1
fi
instances_client="$(admin get clients -r freedriver -q clientId=freedriver-instances --fields id --format csv --noquotes | csv_id)"
instances_sa="$(admin get "clients/${instances_client}/service-account-user" -r freedriver --fields id --format csv --noquotes | csv_id)"
admin get "users/${instances_sa}/role-mappings" -r freedriver > "$work/instances-roles.json"
python3 - "$work/instances-roles.json" <<'PY'
import json, sys
mappings = json.load(open(sys.argv[1], encoding="utf-8"))
client = (mappings.get("clientMappings") or {}).get("realm-management") or {}
names = [role.get("name") for role in (client.get("mappings") or [])]
if "manage-users" not in names:
    sys.exit("instances service account is missing manage-users")
print("instances service account has manage-users")
PY
admin get groups -r freedriver -q search=mqtt-instance -q exact=true > "$work/mqtt-groups.json"
python3 - "$work/mqtt-groups.json" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1], encoding="utf-8"))
houses = [group for group in groups if group.get("name") == "mqtt-instance" and not group.get("parentId")]
if len(houses) != 1:
    sys.exit("top-level mqtt-instance group was not found")
attributes = houses[0].get("attributes") or {}
if attributes:
    sys.exit("mqtt-instance must not carry topic attributes")
print("mqtt-instance has no topic attributes")
PY
sa_id="$(admin get users -r freedriver -q username=service-account-freedriver-mqtt -q exact=true --fields id --format csv --noquotes | csv_id)"
admin get "users/${sa_id}" -r freedriver > "$work/mqtt-sa.json"
admin get "users/${sa_id}/groups" -r freedriver > "$work/mqtt-sa-groups.json"
admin get users/profile -r freedriver > "$work/user-profile.json"
python3 - "$work/mqtt-sa.json" "$work/mqtt-sa-groups.json" "$work/user-profile.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
groups = json.load(open(sys.argv[2], encoding="utf-8"))
profile = json.load(open(sys.argv[3], encoding="utf-8"))
attrs = user.get("attributes") or {}
pub = set(attrs.get("mqtt_publ_topic") or [])
sub = set(attrs.get("mqtt_subs_topic") or [])
if pub != {"freedriver/v1/+/commands", "freedriver/v1/+/command-lists"}:
    sys.exit("service account publish topics are %s" % sorted(pub))
if sub != {"freedriver/v1/+/appliances", "freedriver/v1/+/scheduled"}:
    sys.exit("service account subscribe topics are %s" % sorted(sub))
if (attrs.get("mqtt_superuser") or []) != ["false"]:
    sys.exit("mqtt_superuser is not false")
if not any(group.get("name") == "portal-broker" for group in groups):
    sys.exit("service account is outside portal-broker")
wanted = {
    "label": False,
    "mqtt_publ_topic": True,
    "mqtt_subs_topic": True,
    "mqtt_superuser": False,
}
found = {item.get("name"): bool(item.get("multivalued")) for item in profile.get("attributes") or []}
for name, multi in wanted.items():
    if found.get(name) is not multi:
        sys.exit("profile %s multivalued=%s" % (name, found.get(name)))
print("service account topics and multivalued profile attributes match")
PY

python3 - "$work/secrets/invite.env" "$work/token-form" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlencode
secret = Path(sys.argv[1]).read_text(encoding="utf-8").split("=", 1)[1].strip()
Path(sys.argv[2]).write_text(urlencode({
    "grant_type": "client_credentials",
    "client_id": "freedriver-invite",
    "client_secret": secret,
}), encoding="utf-8")
PY
chmod 600 "$work/token-form"
curl -sS -o "$work/token.json" --data-binary @"$work/token-form" \
  "${BASE}/realms/freedriver/protocol/openid-connect/token"
rm -f "$work/token-form"
python3 - "$work/token.json" "$work/token" <<'PY'
import json, sys
from pathlib import Path
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token")
if not token:
    print(data.get("error") or "no service-account token", file=sys.stderr)
    sys.exit(1)
Path(sys.argv[2]).write_text(token, encoding="utf-8")
PY
chmod 600 "$work/token"
rm -f "$work/token.json"

widen_group_permission
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
  SECRETS_ENV="$work/secrets.env" \
  bash scripts/provision-keycloak-freedriver.sh
assert_invite_scopes
assert_portal_jobs_token
assert_instances_token

admin create users -r freedriver -s username=outside-invite -s enabled=true >/dev/null
outside_id="$(admin get users -r freedriver -q username=outside-invite -q exact=true --fields id --format csv --noquotes | csv_id)"
kaze_id="$(admin get users -r freedriver -q username=kaze -q exact=true --fields id --format csv --noquotes | csv_id)"
other_group="$(admin get groups -r freedriver -q search=other-group -q exact=true --fields id --format csv --noquotes | csv_id)"
phone_group="$(admin get groups -r freedriver -q search=phone-sign-in -q exact=true --fields id --format csv --noquotes | csv_id)"
portal_role="$(admin get roles/portal-admin -r freedriver --fields id --format csv --noquotes | csv_id)"
dashboard_role="$(admin get roles/dashboard -r freedriver --fields id --format csv --noquotes | csv_id)"
api_client="$(admin get clients -r freedriver -q clientId=freedriver-api --fields id --format csv --noquotes | csv_id)"

http() {
  local method="$1" url="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS --max-time 30 -o "$work/http.out" -w '%{http_code}' -X "$method" \
      -H "Authorization: Bearer $(cat "$work/token")" \
      -H 'Content-Type: application/json' \
      --data-binary @"$body" \
      "$url"
  else
    curl -sS --max-time 30 -o "$work/http.out" -w '%{http_code}' -X "$method" \
      -H "Authorization: Bearer $(cat "$work/token")" \
      "$url"
  fi
}

dump_invite_perms() {
  local admin_client perm_id
  admin_client="$(admin get clients -r freedriver -q clientId=admin-permissions --fields id --format csv --noquotes | csv_id)"
  admin get "clients/${admin_client}/authz/resource-server/permission/scope" -r freedriver > "$work/perms.json" || return 0
  python3 - "$work/perms.json" "$work/perm-ids" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("permissions") or data.get("results") or []
lines = []
for item in data:
    if isinstance(item, dict) and str(item.get("name", "")).startswith("invite-") and item.get("id"):
        lines.append(item["id"] + " " + item["name"])
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(lines) + ("\n" if lines else ""))
PY
  while read -r perm_id perm_name; do
    [[ -n "$perm_id" ]] || continue
    admin get "clients/${admin_client}/authz/resource-server/permission/scope/${perm_id}?fields=*" -r freedriver > "$work/perm-one.json" || continue
    python3 - "$work/perm-one.json" "$phone_group" "$perm_name" <<'PY'
import json, re, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
scopes = item.get("scopes") or []
scopes_data = item.get("scopesData") or []
uuid = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
scope_names = sorted({
    (s if isinstance(s, str) else (s or {}).get("name"))
    for s in list(scopes) + list(scopes_data)
    if s and not (isinstance(s, str) and uuid.fullmatch(s))
})
resources = item.get("resources") or []
resources_data = item.get("resourcesData") or []
resource_names = []
for resource in list(resources) + list(resources_data):
    if isinstance(resource, str):
        resource_names.append(resource)
    elif isinstance(resource, dict):
        resource_names.append(resource.get("name") or resource.get("id"))
print("permission", sys.argv[3],
      "scopes", scope_names,
      "resources", resource_names,
      "includes_phone_group", sys.argv[2] in resource_names)
PY
    admin get "clients/${admin_client}/authz/resource-server/policy/${perm_id}/associatedPolicies" -r freedriver > "$work/assoc.json" || true
    python3 - "$work/assoc.json" "$perm_name" <<'PY'
import json, sys
raw = open(sys.argv[1], encoding="utf-8").read()
try:
    data = json.loads(raw)
except json.JSONDecodeError:
    print("associated", sys.argv[2], "unreadable")
    raise SystemExit
if isinstance(data, dict):
    data = data.get("policies") or data.get("results") or []
names = [item.get("name") for item in data if isinstance(item, dict)]
print("associated", sys.argv[2], names)
PY
  done < "$work/perm-ids"
  admin get "clients/${admin_client}/authz/resource-server/policy" -r freedriver > "$work/policies.json" || true
  python3 - "$work/policies.json" "$work/policy-id" "$work/policy-type" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("policies") or data.get("results") or []
policy_id, policy_type = "", ""
for item in data:
    if isinstance(item, dict) and item.get("name") == "freedriver-invite-sa":
        policy_id = item.get("id") or ""
        policy_type = item.get("type") or ""
        break
open(sys.argv[2], "w", encoding="utf-8").write(policy_id)
open(sys.argv[3], "w", encoding="utf-8").write(policy_type)
print("invite policy listed", bool(policy_id), "type", policy_type or "missing")
PY
  if [[ -s "$work/policy-id" ]]; then
    policy_id="$(cat "$work/policy-id")"
    policy_type="$(cat "$work/policy-type")"
    admin get "clients/${admin_client}/authz/resource-server/policy/${policy_type}/${policy_id}" -r freedriver > "$work/typed-policy.json" || true
    invite_client="$(admin get clients -r freedriver -q clientId=freedriver-invite --fields id --format csv --noquotes | csv_id)"
    python3 - "$work/typed-policy.json" "$work/token" "$invite_client" <<'PY'
import base64, json, sys
policy = json.load(open(sys.argv[1], encoding="utf-8"))
payload = open(sys.argv[2], encoding="utf-8").read().strip().split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
subject = claims.get("sub")
users = policy.get("users") or []
clients = policy.get("clients") or []
print(
    "invite policy user_count", len(users),
    "subject_matches", subject in users,
    "client_count", len(clients),
    "client_matches", sys.argv[3] in clients,
)
PY
  fi
}

expect() {
  local want="$1" got="$2" label="$3"
  if [[ "$got" != "$want" ]]; then
    echo "${label}: expected ${want}, got ${got}" >&2
    python3 - "$work/http.out" <<'PY' >&2 || true
import sys
raw = open(sys.argv[1], encoding="utf-8", errors="replace").read(500)
if "access_token" in raw or "client_secret" in raw or "FREEDRIVER_INVITE" in raw:
    print("body omitted")
else:
    print(raw)
PY
    dump_invite_perms
    exit 1
  fi
  echo "${label}: ${got}"
}

python3 - "$portal_role" > "$work/portal.json" <<'PY'
import json, sys
json.dump([{"id": sys.argv[1], "name": "portal-admin"}], sys.stdout)
PY
python3 - "$dashboard_role" > "$work/dashboard-role.json" <<'PY'
import json, sys
json.dump([{"id": sys.argv[1], "name": "dashboard"}], sys.stdout)
PY
printf '%s\n' '{"type":"password","value":"not-a-real-password","temporary":true}' > "$work/reset.json"
admin get "users/${outside_id}" -r freedriver > "$work/outside.json"
python3 - "$work/outside.json" > "$work/attrs.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user.setdefault("attributes", {})["note"] = ["nope"]
json.dump(user, sys.stdout)
PY
python3 > "$work/create.json" <<'PY'
import json, sys
json.dump({"username": "invitee-one", "enabled": True, "groups": ["/phone-sign-in"]}, sys.stdout)
PY
python3 > "$work/create-cred.json" <<'PY'
import json, sys
json.dump({
    "username": "invitee-cred",
    "enabled": True,
    "groups": ["/phone-sign-in"],
    "credentials": [{"type": "password", "value": "not-a-real-password", "temporary": True}],
}, sys.stdout)
PY
python3 > "$work/create-other.json" <<'PY'
import json, sys
json.dump({"username": "invitee-other", "enabled": True, "groups": ["/other-group"]}, sys.stdout)
PY
python3 > "$work/create-none.json" <<'PY'
import json, sys
json.dump({"username": "invitee-none", "enabled": True}, sys.stdout)
PY

expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users/${outside_id}/role-mappings/realm" "$work/portal.json")" \
  "grant portal-admin"
expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users/${outside_id}/role-mappings/realm" "$work/dashboard-role.json")" \
  "grant dashboard"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}/reset-password" "$work/reset.json")" \
  "reset password of a user outside phone-sign-in"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}" "$work/attrs.json")" \
  "change attributes of a user outside phone-sign-in"
expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users/${outside_id}/impersonation" )" \
  "impersonate"
expect 403 "$(http GET "${BASE}/admin/realms/freedriver/clients")" \
  "list clients"
expect 403 "$(http GET "${BASE}/admin/realms/freedriver/clients/${api_client}/client-secret")" \
  "read a client secret"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}/groups/${other_group}")" \
  "add a user to a group other than phone-sign-in"
expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users" "$work/create-none.json")" \
  "create a user with no group"
expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users" "$work/create-other.json")" \
  "create a user in a group other than phone-sign-in"
expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users" "$work/create.json")" \
  "create a user in phone-sign-in"
expect 403 "$(http POST "${BASE}/admin/realms/freedriver/users" "$work/create-cred.json")" \
  "create a user in phone-sign-in with credentials"

# kaze is a phone-sign-in member the realm admin created.
admin get "users/${kaze_id}" -r freedriver > "$work/kaze-user.json"
python3 - "$work/kaze-user.json" > "$work/kaze-attr.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user.setdefault("attributes", {})["note"] = ["nope"]
json.dump(user, sys.stdout)
PY
python3 - "$work/kaze-user.json" > "$work/kaze-enabled.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user["enabled"] = False
json.dump(user, sys.stdout)
PY
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${kaze_id}/reset-password" "$work/reset.json")" \
  "reset password of phone-sign-in member kaze"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${kaze_id}" "$work/kaze-attr.json")" \
  "change attributes of phone-sign-in member kaze"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${kaze_id}" "$work/kaze-enabled.json")" \
  "disable phone-sign-in member kaze"
expect 403 "$(http DELETE "${BASE}/admin/realms/freedriver/users/${kaze_id}")" \
  "delete phone-sign-in member kaze"

failed=0
join_code="$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}/groups/${phone_group}")"
if [[ "$join_code" != "204" ]]; then
  echo "add a user to phone-sign-in: expected 204, got ${join_code}" >&2
  python3 - "$work/http.out" <<'PY' >&2 || true
import sys
raw = open(sys.argv[1], encoding="utf-8", errors="replace").read(500)
if "access_token" in raw or "client_secret" in raw or "FREEDRIVER_INVITE" in raw:
    print("body omitted")
else:
    print(raw)
PY
  failed=1
else
  echo "add a user to phone-sign-in: 204"
fi
if [[ "$failed" -ne 0 ]]; then
  dump_invite_perms
  exit 1
fi

admin get "users/${outside_id}" -r freedriver > "$work/joined.json"
python3 - "$work/joined.json" > "$work/joined-attr.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user.setdefault("attributes", {})["note"] = ["nope"]
json.dump(user, sys.stdout)
PY
python3 - "$work/joined.json" > "$work/joined-enabled.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user["enabled"] = False
json.dump(user, sys.stdout)
PY
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}/reset-password" "$work/reset.json")" \
  "reset password of a user just added to phone-sign-in"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}" "$work/joined-attr.json")" \
  "change attributes of a user just added to phone-sign-in"
expect 403 "$(http PUT "${BASE}/admin/realms/freedriver/users/${outside_id}" "$work/joined-enabled.json")" \
  "disable a user just added to phone-sign-in"
expect 403 "$(http DELETE "${BASE}/admin/realms/freedriver/users/${outside_id}")" \
  "delete a user just added to phone-sign-in"

# House topic migration. Separate from the phone-role failure run below.
OLD_HOUSE=11111111-1111-4111-8111-111111111111
NEW_HOUSE=22222222-2222-4222-8222-222222222222
BAD_HOUSE=33333333-3333-4333-8333-333333333333
OUT_HOUSE=44444444-4444-4444-8444-444444444444
admin get groups -r freedriver -q search=mqtt-instance -q exact=true > "$work/mqtt-groups.json"
house_group="$(python3 - "$work/mqtt-groups.json" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [group["id"] for group in groups if group.get("name") == "mqtt-instance" and not group.get("parentId")]
if len(ids) != 1:
    sys.exit("top-level mqtt-instance group was not found")
print(ids[0])
PY
)"
python3 - "$work" "$OLD_HOUSE" "$NEW_HOUSE" "$BAD_HOUSE" "$OUT_HOUSE" <<'PY'
import json, sys
from pathlib import Path
work, old, new, bad, outside = sys.argv[1:]
root = Path(work)

def dump(name, username, publ, subs, label=None):
    attributes = {
        "mqtt_publ_topic": publ,
        "mqtt_subs_topic": subs,
        "mqtt_superuser": ["false"],
    }
    if label:
        attributes["label"] = [label]
    root.joinpath(name).write_text(json.dumps({
        "username": username,
        "enabled": True,
        "attributes": attributes,
    }), encoding="utf-8")

dump("old-house.json", old, [f"freedriver/v1/{old}/appliances"], [f"freedriver/v1/{old}/commands"], "porch")
dump("new-house.json", new,
     [f"freedriver/v1/{new}/appliances", f"freedriver/v1/{new}/scheduled"],
     [f"freedriver/v1/{new}/commands", f"freedriver/v1/{new}/command-lists"],
     "steady")
dump("bad-house.json", bad, [f"freedriver/v1/{bad}/nope"], [f"freedriver/v1/{bad}/commands"], "foreign")
dump("out-house.json", outside, [f"freedriver/v1/{outside}/appliances"], [f"freedriver/v1/{outside}/commands"], "outside")
PY
seed_house() {
  local file="$1"
  local join="$2"
  local username id
  username="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["username"])' "$file")"
  admin_stdin create users -r freedriver -f - < "$file" >/dev/null
  id="$(admin get users -r freedriver -q "username=${username}" -q exact=true --fields id --format csv --noquotes | csv_id)"
  if [[ "$join" == yes ]]; then
    admin update "users/${id}/groups/${house_group}" -r freedriver >/dev/null
  fi
}
seed_house "$work/old-house.json" yes
seed_house "$work/new-house.json" yes
seed_house "$work/bad-house.json" yes
seed_house "$work/out-house.json" no
run_house_provision() {
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
    SECRETS_ENV="$work/secrets.env" \
    bash scripts/provision-keycloak-freedriver.sh
}
assert_house_topics() {
  local phase="$1"
  admin get users -r freedriver -q briefRepresentation=false -q max=1000 > "$work/houses.json"
  python3 - "$work/houses.json" "$phase" "$OLD_HOUSE" "$NEW_HOUSE" "$BAD_HOUSE" "$OUT_HOUSE" <<'PY'
import json, sys
users = json.load(open(sys.argv[1], encoding="utf-8"))
phase, old, new, bad, outside = sys.argv[2:]
by_name = {user.get("username"): user for user in users}

def attrs(name):
    user = by_name.get(name)
    if user is None:
        sys.exit("%s missing user %s" % (phase, name))
    return user.get("attributes") or {}

def topics(name):
    found = attrs(name)
    return list(found.get("mqtt_publ_topic") or []), list(found.get("mqtt_subs_topic") or [])

def same(got, want):
    return len(got) == len(want) and set(got) == set(want)

old_attrs = attrs(old)
if not same(topics(old)[0], [f"freedriver/v1/{old}/appliances", f"freedriver/v1/{old}/scheduled"]):
    sys.exit("%s old publish %s" % (phase, topics(old)[0]))
if not same(topics(old)[1], [f"freedriver/v1/{old}/commands", f"freedriver/v1/{old}/command-lists"]):
    sys.exit("%s old subscribe %s" % (phase, topics(old)[1]))
if old_attrs.get("label") != ["porch"] or old_attrs.get("mqtt_superuser") != ["false"]:
    sys.exit("%s old label or mqtt_superuser changed" % phase)
new_attrs = attrs(new)
if not same(topics(new)[0], [f"freedriver/v1/{new}/appliances", f"freedriver/v1/{new}/scheduled"]):
    sys.exit("%s new publish changed" % phase)
if not same(topics(new)[1], [f"freedriver/v1/{new}/commands", f"freedriver/v1/{new}/command-lists"]):
    sys.exit("%s new subscribe changed" % phase)
if new_attrs.get("label") != ["steady"]:
    sys.exit("%s new label changed" % phase)
bad_attrs = attrs(bad)
if topics(bad) != ([f"freedriver/v1/{bad}/nope"], [f"freedriver/v1/{bad}/commands"]):
    sys.exit("%s foreign topics changed %s" % (phase, topics(bad)))
if bad_attrs.get("label") != ["foreign"]:
    sys.exit("%s foreign label changed" % phase)
out_attrs = attrs(outside)
if topics(outside) != ([f"freedriver/v1/{outside}/appliances"], [f"freedriver/v1/{outside}/commands"]):
    sys.exit("%s outside user changed %s" % (phase, topics(outside)))
if out_attrs.get("label") != ["outside"]:
    sys.exit("%s outside label changed" % phase)
print(phase, "house topic migration holds")
PY
}
set +e
run_house_provision >"$work/migrate.out" 2>&1
migrate=$?
set -e
if [[ "$migrate" -eq 0 ]]; then
  echo "house migration should name the foreign topic user" >&2
  exit 1
fi
if ! grep -F "$BAD_HOUSE" "$work/migrate.out"; then
  echo "house migration did not name the foreign topic user" >&2
  exit 1
fi
assert_house_topics seeded
printf 'FREEDRIVER_SEED_PHONE=\n' > "$work/secrets.env"
set +e
run_house_provision >"$work/migrate-empty.out" 2>&1
migrate_empty=$?
set -e
if [[ "$migrate_empty" -eq 0 ]]; then
  echo "empty-seed provision should name the foreign topic user" >&2
  exit 1
fi
if ! grep -F "$BAD_HOUSE" "$work/migrate-empty.out"; then
  echo "empty-seed provision did not name the foreign topic user" >&2
  exit 1
fi
assert_house_topics "empty-seed"
printf 'FREEDRIVER_SEED_PHONE=+15555550143\n' > "$work/secrets.env"

# A failed role check must not replace the phone number.
# dispatcher is created by the provision script and stays outside the allow-list.
admin add-roles -r freedriver --uusername kaze --rolename dispatcher >/dev/null
printf 'FREEDRIVER_SEED_PHONE=+15555550999\n' > "$work/secrets.env"
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
  SECRETS_ENV="$work/secrets.env" \
  bash scripts/provision-keycloak-freedriver.sh >"$work/second.out" 2>&1
second=$?
set -e
if [[ "$second" -eq 0 ]]; then
  echo "second provision should stop when kaze has dispatcher" >&2
  exit 1
fi
if ! grep -q 'phone attribute was not changed' "$work/second.out"; then
  echo "second provision did not report that the phone attribute was unchanged" >&2
  grep -v 'SECRET\|password\|token' "$work/second.out" | tail -n 30 >&2 || true
  exit 1
fi
kaze_id="$(admin get users -r freedriver -q username=kaze -q exact=true --fields id --format csv --noquotes | csv_id)"
admin get "users/${kaze_id}" -r freedriver > "$work/kaze.json"
python3 - "$work/kaze.json" <<'PY'
import json, os, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
phone = (user.get("attributes") or {}).get("phone")
if phone != ["+15555550143"]:
    sys.exit("phone attribute changed or was missing")
print("phone attribute unchanged after the refused role check")
PY

# Second throwaway with the production hostname settings. Token and admin reads
# stay on plain HTTP with redirects left unfollowed. The issuer is the public realm.
prove_instances_hostname() {
  local host_work ready
  echo "starting hostname Keycloak"
  docker rm -f "$HOST_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$HOST_CONTAINER" --memory=2g --memory-swap=2g \
    -p "127.0.0.1:${HOST_PORT}:8080" \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    -e KC_HOSTNAME=https://auth.freedriver.io \
    -e KC_HOSTNAME_ADMIN=http://127.0.0.1:8081 \
    -e KC_HTTP_ENABLED=true \
    -e KC_PROXY_HEADERS=xforwarded \
    -e JAVA_OPTS_APPEND="-Xms256m -Xmx768m" \
    -v "${PROVIDER_JAR}:/opt/keycloak/providers/freedriver-sms-otp.jar:ro" \
    "$IMAGE" start-dev >/dev/null
  ready=0
  for _ in $(seq 1 90); do
    if docker exec "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
        --config /tmp/kcadm-admin.config \
        --server http://127.0.0.1:8080 --realm master --user admin --password admin \
        >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 2
  done
  if [[ "$ready" != 1 ]]; then
    echo "hostname Keycloak did not become ready" >&2
    docker logs "$HOST_CONTAINER" 2>&1 | tail -n 40 >&2 || true
    exit 1
  fi
  host_work="$(mktemp -d)"
  chmod 700 "$host_work"
  openssl rand -hex 24 > "$host_work/techops.pass"
  chmod 600 "$host_work/techops.pass"
  docker exec "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh create users -r master \
    --config /tmp/kcadm-admin.config -s username=techops -s enabled=true >/dev/null
  TECHOPS_PASS="$(cat "$host_work/techops.pass")"
  export TECHOPS_PASS
  docker exec -e TECHOPS_PASS "$HOST_CONTAINER" \
    sh -c '/opt/keycloak/bin/kcadm.sh set-password --config /tmp/kcadm-admin.config -r master --username techops --new-password "$TECHOPS_PASS"' \
    >/dev/null
  unset TECHOPS_PASS
  docker exec "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh add-roles -r master \
    --config /tmp/kcadm-admin.config --uusername techops --rolename admin >/dev/null
  docker exec "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh create realms \
    --config /tmp/kcadm-admin.config -s realm=freedriver -s enabled=true >/dev/null
  docker exec "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh create groups -r freedriver \
    --config /tmp/kcadm-admin.config -s name=phone-sign-in >/dev/null
  docker exec "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh get users/profile -r freedriver \
    --config /tmp/kcadm-admin.config > "$host_work/profile.json"
  python3 - "$host_work/profile.json" <<'PY'
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
  docker exec -i "$HOST_CONTAINER" /opt/keycloak/bin/kcadm.sh update users/profile -r freedriver \
    --config /tmp/kcadm-admin.config -f - < "$host_work/profile.json" >/dev/null
  printf 'FREEDRIVER_SEED_PHONE=+15555550143\n' > "$host_work/secrets.env"
  chmod 600 "$host_work/secrets.env"
  mkdir -p "$host_work/secrets"
  chmod 700 "$host_work/secrets"
  PROVISION_ALLOW_NON_ROOT=1 \
    KEYCLOAK_CONTAINER="$HOST_CONTAINER" \
    TECHOPS_PASS_FILE="$host_work/techops.pass" \
    SECRET_FILE="$host_work/secrets/api.secret" \
    INVITE_ENV_FILE="$host_work/secrets/invite.env" \
    INSTANCES_ENV_FILE="$host_work/secrets/instances.env" \
    PORTAL_JOBS_ENV_FILE="$host_work/secrets/portal-jobs.env" \
    GRAFANA_ENV_FILE="$host_work/secrets/grafana.env" \
    MQTT_ENV_FILE="$host_work/secrets/mqtt.env" \
    MQTT_OAUTH_DIR="$host_work/secrets/mqtt-oauth.d" \
    AUTONOMY_MCP_ENV_FILE="$host_work/secrets/autonomy-mcp.env" \
    SECRETS_ENV="$host_work/secrets.env" \
    bash scripts/provision-keycloak-freedriver.sh
  instances_admin_probe "$HOST_BASE" "$host_work/secrets/instances.env" "$host_work" \
    "https://auth.freedriver.io/realms/freedriver"
  rm -rf "$host_work"
  echo "hostname instances admin ok"
}

prove_instances_hostname

echo "invite service account refusals ok"
