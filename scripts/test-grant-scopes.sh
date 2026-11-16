#!/usr/bin/env bash
# Throwaway Keycloak proof for #527: grant scopes on a scratch realm.
# A token carries exactly the grants it was given and the roles those grants
# need. Exchange cannot add dashboard or appliances.write. Prints claims only.
# Secrets stay in the work directory.
# Prefers docker. Without docker, starts Keycloak 26.7.5 from the release tarball.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER=freedriver-grant-scopes-kc-test
PORT=18082
BASE="http://127.0.0.1:${PORT}"
IMAGE=quay.io/keycloak/keycloak:26.7.5
KC_VERSION=26.7.5
GROK_REDIRECT='https://grok.com/connectors-oauth-exchange-code/'
JAVA21=/usr/lib/jvm/java-21-openjdk

if [[ "${KEYCLOAK_CONTAINER:-}" == "freedriver-web-keycloak-1" || "$CONTAINER" == "freedriver-web-keycloak-1" ]]; then
  echo "refusing to run the grant-scopes proof against the production Keycloak container" >&2
  exit 1
fi
unset FREEDRIVER_DEVICE_CLIENTS

work="$(mktemp -d)"
chmod 700 "$work"
kc_pid=""
USE_DOCKER=0
if command -v docker >/dev/null; then
  USE_DOCKER=1
fi

cleanup() {
  if [[ -n "$kc_pid" ]]; then
    kill "$kc_pid" >/dev/null 2>&1 || true
    wait "$kc_pid" >/dev/null 2>&1 || true
  fi
  if [[ "$USE_DOCKER" -eq 1 ]]; then
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

if [[ "$USE_DOCKER" -eq 1 ]]; then
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$CONTAINER" --memory=2g --memory-swap=2g \
    -p "127.0.0.1:${PORT}:8080" \
    -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
    -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
    -e JAVA_OPTS_APPEND="-Xms256m -Xmx768m" \
    "$IMAGE" start-dev >/dev/null
  KCADM=(docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh)
  KCADM_CONFIG=/tmp/kcadm-admin.config
  KC_SERVER=http://127.0.0.1:8080
else
  if [[ ! -x "$JAVA21/bin/java" ]]; then
    echo "docker is missing and ${JAVA21} is missing" >&2
    exit 1
  fi
  tarball="/tmp/keycloak-${KC_VERSION}.tar.gz"
  if [[ ! -s "$tarball" ]]; then
    echo "downloading Keycloak ${KC_VERSION}"
    curl -fsSL -o "$tarball" \
      "https://github.com/keycloak/keycloak/releases/download/${KC_VERSION}/keycloak-${KC_VERSION}.tar.gz"
  fi
  tar -xzf "$tarball" -C "$work"
  kc_home="$work/keycloak-${KC_VERSION}"
  export JAVA_HOME="$JAVA21"
  export PATH="$JAVA_HOME/bin:$PATH"
  export KC_BOOTSTRAP_ADMIN_USERNAME=admin
  export KC_BOOTSTRAP_ADMIN_PASSWORD=admin
  "$kc_home/bin/kc.sh" start-dev --http-port="$PORT" --hostname-strict=false \
    >"$work/keycloak.log" 2>&1 &
  kc_pid=$!
  KCADM=("$kc_home/bin/kcadm.sh")
  KCADM_CONFIG="$work/kcadm-admin.config"
  KC_SERVER="$BASE"
fi

echo "waiting for throwaway Keycloak"
ready=0
for _ in $(seq 1 90); do
  if "${KCADM[@]}" config credentials \
      --config "$KCADM_CONFIG" \
      --server "$KC_SERVER" --realm master --user admin --password admin \
      >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" != 1 ]]; then
  echo "throwaway Keycloak did not become ready" >&2
  if [[ "$USE_DOCKER" -eq 1 ]]; then
    docker logs "$CONTAINER" 2>&1 | tail -n 40 >&2 || true
  else
    tail -n 40 "$work/keycloak.log" >&2 || true
  fi
  exit 1
fi

admin() {
  local verb="$1"
  shift
  "${KCADM[@]}" "$verb" --config "$KCADM_CONFIG" "$@"
}

admin_stdin() {
  local verb="$1"
  shift
  if [[ "$USE_DOCKER" -eq 1 ]]; then
    docker exec -i "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$verb" --config "$KCADM_CONFIG" "$@"
  else
    "${KCADM[@]}" "$verb" --config "$KCADM_CONFIG" "$@"
  fi
}

csv_id() {
  sed '/^id$/d' | tr -d '\r' | awk 'NF { print; exit }'
}

openssl rand -hex 24 > "$work/techops.pass"
chmod 600 "$work/techops.pass"
admin create users -r master -s username=techops -s enabled=true >/dev/null
TECHOPS_PASS="$(cat "$work/techops.pass")"
admin set-password -r master --username techops --new-password "$TECHOPS_PASS" >/dev/null
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

printf 'FREEDRIVER_SEED_PHONE=+15555550100\n' > "$work/secrets.env"
chmod 600 "$work/secrets.env"
mkdir -p "$work/secrets"
chmod 700 "$work/secrets"

provision_env=(
  PROVISION_ALLOW_NON_ROOT=1
  KEYCLOAK_CONTAINER="$CONTAINER"
  TECHOPS_PASS_FILE="$work/techops.pass"
  SECRET_FILE="$work/secrets/api.secret"
  INVITE_ENV_FILE="$work/secrets/invite.env"
  INSTANCES_ENV_FILE="$work/secrets/instances.env"
  GRAFANA_ENV_FILE="$work/secrets/grafana.env"
  MQTT_ENV_FILE="$work/secrets/mqtt.env"
  MQTT_OAUTH_DIR="$work/secrets/mqtt-oauth.d"
  AUTONOMY_MCP_ENV_FILE="$work/secrets/autonomy-mcp.env"
  PORTAL_JOBS_ENV_FILE="$work/secrets/portal-jobs.env"
  SECRETS_ENV="$work/secrets.env"
)
if [[ "$USE_DOCKER" -eq 0 ]]; then
  provision_env+=(
    KCADM_BIN="${KCADM[0]}"
    KEYCLOAK_SERVER="$KC_SERVER"
  )
fi
env "${provision_env[@]}" bash scripts/provision-keycloak-freedriver.sh | tee "$work/provision-1.log"

if ! grep -q '^FREEDRIVER_AUTONOMY_MCP_CLIENT_SECRET=.\+' "$work/secrets/autonomy-mcp.env"; then
  echo "autonomy MCP env file is missing a secret value" >&2
  exit 1
fi
if ! grep -q 'Created role mcp-debug' "$work/provision-1.log"; then
  echo "first provision must create role mcp-debug" >&2
  exit 1
fi
if ! grep -q 'Created client scope appliances.read' "$work/provision-1.log"; then
  echo "first provision must create grant scopes" >&2
  exit 1
fi

python3 - "$GROK_REDIRECT" "$work/dcr-body.json" <<'PY'
import json, sys
json.dump({
    "client_name": "scratch-grant-scopes",
    "redirect_uris": [sys.argv[1]],
    "token_endpoint_auth_method": "none",
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
}, open(sys.argv[2], "w", encoding="utf-8"))
PY

dcr_status="$(curl -sS -o "$work/dcr.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data-binary @"$work/dcr-body.json" \
  "${BASE}/realms/freedriver/clients-registrations/openid-connect")"
if [[ "$dcr_status" != 201 ]]; then
  echo "DCR registration HTTP ${dcr_status}" >&2
  python3 - "$work/dcr.json" <<'PY' >&2
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print("error", data.get("error_description") or data.get("error") or data)
PY
  exit 1
fi

DCR_CLIENT_ID="$(python3 - "$work/dcr.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
client_id = data.get("client_id")
if not client_id:
    raise SystemExit("DCR response missing client_id")
print(client_id)
PY
)"
rm -f "$work/dcr.json" "$work/dcr-body.json"
echo "DCR client registered"

env "${provision_env[@]}" bash scripts/provision-keycloak-freedriver.sh | tee "$work/provision-2.log"
if grep -q 'Created role mcp-debug' "$work/provision-2.log"; then
  echo "second provision must not create role mcp-debug again" >&2
  exit 1
fi
if grep -q 'Created client scope appliances.read' "$work/provision-2.log"; then
  echo "second provision must not create grant scopes again" >&2
  exit 1
fi
echo "second provision changed nothing"

dcr_uuid="$(admin get clients -r freedriver -q "clientId=${DCR_CLIENT_ID}" --fields id --format csv --noquotes | csv_id)"
# Scratch-only: ROPC mints a DCR-azp user token without grok.com auth-code.
# Anonymous DCR clients require consent; skip it here so password grant works.
admin update "clients/${dcr_uuid}" -r freedriver \
  -s directAccessGrantsEnabled=true \
  -s consentRequired=false >/dev/null

decode_claims() {
  local json_path="$1"
  local out_path="$2"
  python3 - "$json_path" "$out_path" <<'PY'
import base64, json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token")
if not token:
    print(data.get("error_description") or data.get("error") or "no access token", file=sys.stderr)
    sys.exit(1)
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
scope = claims.get("scope") or ""
if isinstance(scope, list):
    scope_set = set(scope)
else:
    scope_set = set(str(scope).split())
keep = {
    "sub": claims.get("sub"),
    "azp": claims.get("azp"),
    "aud": claims.get("aud"),
    "scope": sorted(scope_set),
    "roles": (claims.get("realm_access") or {}).get("roles") or [],
}
json.dump(keep, open(sys.argv[2], "w", encoding="utf-8"))
PY
}

GRANTS='appliances.read appliances.write mcp.debug'

assert_row() {
  local label="$1"
  local claims="$2"
  python3 - "$label" "$claims" <<'PY'
import json, sys
label, path = sys.argv[1], sys.argv[2]
claims = json.load(open(path, encoding="utf-8"))
scope = set(claims.get("scope") or [])
roles = set(claims.get("roles") or [])
grants = {"appliances.read", "appliances.write", "mcp.debug", "phones.admin"}
got_grants = sorted(scope & grants)
got_roles = sorted(r for r in roles if r in {"mcp-user", "dashboard", "mcp-debug", "portal-admin"})
print(f"{label}: scope={','.join(got_grants) or '(none)'} roles={','.join(got_roles) or '(none)'}")
if "phones.admin" in scope or "portal-admin" in roles:
    raise SystemExit(f"{label}: must never carry phones.admin or portal-admin")
PY
}

ropc() {
  local username="$1"
  local scope="$2"
  local out="$3"
  local password
  password="$(openssl rand -hex 16)"
  admin set-password -r freedriver --username "$username" --new-password "$password" >/dev/null
  python3 - "$DCR_CLIENT_ID" "$username" "$password" "$scope" "$work/ropc-form" <<'PY'
from pathlib import Path
from urllib.parse import urlencode
import sys
Path(sys.argv[5]).write_text(urlencode({
    "grant_type": "password",
    "client_id": sys.argv[1],
    "username": sys.argv[2],
    "password": sys.argv[3],
    "scope": sys.argv[4],
}), encoding="utf-8")
PY
  chmod 600 "$work/ropc-form"
  unset password
  curl -sS -o "$work/ropc.json" --data-binary @"$work/ropc-form" \
    "${BASE}/realms/freedriver/protocol/openid-connect/token"
  rm -f "$work/ropc-form"
  decode_claims "$work/ropc.json" "$out"
  python3 - "$work/ropc.json" "$out.jwt" <<'PY'
import json, sys
from pathlib import Path
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token")
if not token:
    sys.exit(1)
Path(sys.argv[2]).write_text(token, encoding="utf-8")
PY
  chmod 600 "$out.jwt"
  rm -f "$work/ropc.json"
}

exchange_once() {
  local subject_jwt="$1"
  local extra_scope="$2"
  local out="$3"
  python3 - "$work/secrets/autonomy-mcp.env" "$subject_jwt" "$extra_scope" "$work/exchange-form" <<'PY'
from pathlib import Path
from urllib.parse import urlencode
import sys
secret = Path(sys.argv[1]).read_text(encoding="utf-8").split("=", 1)[1].strip()
subject = Path(sys.argv[2]).read_text(encoding="utf-8").strip()
extra = sys.argv[3]
fields = {
    "grant_type": "urn:ietf:params:oauth:grant-type:token-exchange",
    "client_id": "freedriver-autonomy-mcp",
    "client_secret": secret,
    "subject_token": subject,
    "subject_token_type": "urn:ietf:params:oauth:token-type:access_token",
    "requested_token_type": "urn:ietf:params:oauth:token-type:access_token",
    "audience": "freedriver-api",
}
if extra:
    fields["scope"] = extra
Path(sys.argv[4]).write_text(urlencode(fields), encoding="utf-8")
PY
  chmod 600 "$work/exchange-form"
  local status
  status="$(curl -sS -o "$work/exchanged.json" -w '%{http_code}' \
    --data-binary @"$work/exchange-form" \
    "${BASE}/realms/freedriver/protocol/openid-connect/token")"
  rm -f "$work/exchange-form"
  python3 - "$work/exchanged.json" "$out" "$status" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
status = sys.argv[3]
out = {
    "http": int(status),
    "error": data.get("error"),
    "error_description": data.get("error_description"),
}
token = data.get("access_token")
if token:
    import base64
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
    scope = claims.get("scope") or ""
    if isinstance(scope, list):
        scope_set = set(scope)
    else:
        scope_set = set(str(scope).split())
    out["scope"] = sorted(scope_set)
    out["roles"] = (claims.get("realm_access") or {}).get("roles") or []
    aud = claims.get("aud") or []
    if isinstance(aud, str):
        aud = [aud]
    out["aud"] = aud
json.dump(out, open(sys.argv[2], "w", encoding="utf-8"))
PY
  rm -f "$work/exchanged.json"
}

admin create users -r freedriver \
  -s username=grant-full \
  -s enabled=true \
  -s emailVerified=true \
  -s firstName=Grant \
  -s lastName=Full \
  -s email=grant-full@freedriver.invalid \
  -s 'requiredActions=[]' >/dev/null
admin add-roles -r freedriver --uusername grant-full \
  --rolename mcp-user --rolename dashboard --rolename portal-admin >/dev/null

admin create users -r freedriver \
  -s username=grant-read \
  -s enabled=true \
  -s emailVerified=true \
  -s firstName=Grant \
  -s lastName=Read \
  -s email=grant-read@freedriver.invalid \
  -s 'requiredActions=[]' >/dev/null
admin add-roles -r freedriver --uusername grant-read --rolename mcp-user >/dev/null

ropc grant-full "openid" "$work/row-none.json"
python3 - "$work/row-none.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding="utf-8"))
scope, roles = set(c.get("scope") or []), set(c.get("roles") or [])
if scope & {"appliances.read", "appliances.write", "mcp.debug", "phones.admin"}:
    raise SystemExit("no grant scope: " + ",".join(sorted(scope)))
if "mcp-user" not in roles:
    raise SystemExit("no grant roles: " + ",".join(sorted(roles)))
if "dashboard" in roles:
    raise SystemExit("no grant must not carry dashboard")
print("row no-grant: scope has no grant; roles mcp-user")
PY
assert_row "no-grant" "$work/row-none.json"

ropc grant-full "openid appliances.read" "$work/row-read.json"
python3 - "$work/row-read.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding="utf-8"))
scope, roles = set(c.get("scope") or []), set(c.get("roles") or [])
if "appliances.read" not in scope:
    raise SystemExit("read missing appliances.read: " + ",".join(sorted(scope)))
if scope & {"appliances.write", "mcp.debug"}:
    raise SystemExit("read extra grants: " + ",".join(sorted(scope)))
if roles & {"mcp-user"} != {"mcp-user"} or "dashboard" in roles:
    raise SystemExit("read roles: " + ",".join(sorted(roles)))
print("row appliances.read: that scope; roles mcp-user")
PY
assert_row "appliances.read" "$work/row-read.json"

ropc grant-full "openid appliances.read appliances.write" "$work/row-rw.json"
python3 - "$work/row-rw.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding="utf-8"))
scope, roles = set(c.get("scope") or []), set(c.get("roles") or [])
if not {"appliances.read", "appliances.write"} <= scope:
    raise SystemExit("rw missing grants: " + ",".join(sorted(scope)))
if "mcp-user" not in roles or "dashboard" not in roles:
    raise SystemExit("rw roles: " + ",".join(sorted(roles)))
print("row appliances.read appliances.write: both; roles mcp-user, dashboard")
PY
assert_row "read+write" "$work/row-rw.json"

ropc grant-full "openid ${GRANTS}" "$work/row-all.json"
python3 - "$work/row-all.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding="utf-8"))
scope, roles = set(c.get("scope") or []), set(c.get("roles") or [])
if "mcp.debug" in scope or "mcp-debug" in roles:
    raise SystemExit("all-grants leaked mcp.debug")
if "portal-admin" in roles or "phones.admin" in scope:
    raise SystemExit("all-grants leaked portal-admin")
print("row all grants, first user: no mcp.debug (user lacks mcp-debug); never portal-admin")
PY
assert_row "all-grants" "$work/row-all.json"

ropc grant-read "openid appliances.write" "$work/row-write-denied.json"
python3 - "$work/row-write-denied.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding="utf-8"))
scope, roles = set(c.get("scope") or []), set(c.get("roles") or [])
if "appliances.write" in scope:
    raise SystemExit("second user received appliances.write")
if "dashboard" in roles:
    raise SystemExit("second user received dashboard")
if "mcp-user" not in roles:
    raise SystemExit("second user roles: " + ",".join(sorted(roles)))
print("row appliances.write, second user: scope absent; roles mcp-user")
PY
assert_row "write-denied" "$work/row-write-denied.json"

# Subject token that holds appliances.read only, then three exchanges.
ropc grant-full "openid appliances.read" "$work/subject-read.json"

echo "exchange 1: no scope parameter"
exchange_once "$work/subject-read.json.jwt" "" "$work/ex-none.json"
echo "exchange 2: scope=appliances.read"
exchange_once "$work/subject-read.json.jwt" "appliances.read" "$work/ex-read.json"
echo "exchange 3: scope=appliances.read appliances.write"
exchange_once "$work/subject-read.json.jwt" "appliances.read appliances.write" "$work/ex-wide.json"

python3 - "$work/ex-none.json" "$work/ex-read.json" "$work/ex-wide.json" <<'PY'
import json, sys

def load(path):
    return json.load(open(path, encoding="utf-8"))

rows = [
    ("1 no scope", load(sys.argv[1])),
    ("2 appliances.read", load(sys.argv[2])),
    ("3 appliances.read appliances.write", load(sys.argv[3])),
]
widen = False
for label, row in rows:
    http = row.get("http")
    scope = set(row.get("scope") or [])
    roles = set(row.get("roles") or [])
    aud = row.get("aud") or []
    if isinstance(aud, str):
        aud = [aud]
    print(f"exchange {label}: http={http} scope={','.join(sorted(scope)) or '(none)'} roles={','.join(sorted(r for r in roles if r in {'mcp-user','dashboard','mcp-debug','portal-admin'})) or '(none)'} aud={','.join(aud) or '(none)'}")
    if row.get("error"):
        print(f"  error {row.get('error')}: {row.get('error_description')}")
    if http == 200:
        if "dashboard" in roles or "appliances.write" in scope:
            widen = True
            print("  WIDEN")
if widen:
    raise SystemExit("exchange widened to dashboard or appliances.write")
print("exchange: none returned dashboard or appliances.write")
none = rows[0][1]
if none.get("http") != 200:
    raise SystemExit("exchange 1 (no extra scope) must be HTTP 200")
if "mcp-user" not in set(none.get("roles") or []):
    raise SystemExit("exchange 1 must carry mcp-user")
none_aud = none.get("aud") or []
if isinstance(none_aud, str):
    none_aud = [none_aud]
if "freedriver-api" not in none_aud:
    raise SystemExit("exchange 1 must include audience freedriver-api")
if rows[0][1].get("http") == 200 and rows[2][1].get("http") == 200:
    print("#530: Keycloak narrows a fixed list; autonomy-mcp can send every grant")
elif rows[2][1].get("http") != 200:
    print("#530: Keycloak refuses a wider request; autonomy-mcp must send the caller's own scopes")
else:
    print("#530: record the three answers above")
PY
echo "grant scopes proof ok"
