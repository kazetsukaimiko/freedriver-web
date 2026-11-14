#!/usr/bin/env bash
# Throwaway Keycloak proof for #491: exchange a DCR-issued token for one
# whose audience is freedriver-api and whose sub and realm roles match.
# Prints claims only. Secrets stay in the work directory.
# Prefers docker. Without docker, starts Keycloak 26.7.5 from the release tarball.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER=freedriver-token-exchange-kc-test
PORT=18081
BASE="http://127.0.0.1:${PORT}"
IMAGE=quay.io/keycloak/keycloak:26.7.5
KC_VERSION=26.7.5
GROK_REDIRECT='https://grok.com/connectors-oauth-exchange-code/'
JAVA21=/usr/lib/jvm/java-21-openjdk

if [[ "${KEYCLOAK_CONTAINER:-}" == "freedriver-web-keycloak-1" || "$CONTAINER" == "freedriver-web-keycloak-1" ]]; then
  echo "refusing to run the token-exchange proof against the production Keycloak container" >&2
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
  "${KCADM[@]}" "$verb" --config "$KCADM_CONFIG" "$@"
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
env "${provision_env[@]}" bash scripts/provision-keycloak-freedriver.sh

if ! grep -q '^FREEDRIVER_AUTONOMY_MCP_CLIENT_SECRET=.\+' "$work/secrets/autonomy-mcp.env"; then
  echo "autonomy MCP env file is missing a secret value" >&2
  exit 1
fi

python3 - "$GROK_REDIRECT" "$work/dcr-body.json" <<'PY'
import json, sys
json.dump({
    "client_name": "scratch-token-exchange",
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

dcr_uuid="$(admin get clients -r freedriver -q "clientId=${DCR_CLIENT_ID}" --fields id --format csv --noquotes | csv_id)"
# Scratch-only: ROPC mints a DCR-azp user token without grok.com auth-code.
# Anonymous DCR clients require consent; skip it here so password grant works.
admin update "clients/${dcr_uuid}" -r freedriver \
  -s directAccessGrantsEnabled=true \
  -s consentRequired=false >/dev/null

PROOF_PASS="$(openssl rand -hex 16)"
admin create users -r freedriver \
  -s username=exchange-proof \
  -s enabled=true \
  -s emailVerified=true \
  -s firstName=Exchange \
  -s lastName=Proof \
  -s email=exchange-proof@freedriver.invalid \
  -s 'requiredActions=[]' >/dev/null
admin set-password -r freedriver --username exchange-proof --new-password "$PROOF_PASS" >/dev/null
admin add-roles -r freedriver --uusername exchange-proof --rolename dashboard --rolename mcp-user --rolename portal-admin >/dev/null

python3 - "$DCR_CLIENT_ID" "$PROOF_PASS" "$work/ropc-form" <<'PY'
from pathlib import Path
from urllib.parse import urlencode
import sys
Path(sys.argv[3]).write_text(urlencode({
    "grant_type": "password",
    "client_id": sys.argv[1],
    "username": "exchange-proof",
    "password": sys.argv[2],
    "scope": "openid",
}), encoding="utf-8")
PY
chmod 600 "$work/ropc-form"
unset PROOF_PASS
curl -sS -o "$work/subject.json" --data-binary @"$work/ropc-form" \
  "${BASE}/realms/freedriver/protocol/openid-connect/token"
rm -f "$work/ropc-form"

python3 - "$work/subject.json" "$work/subject.jwt" "$work/subject-claims.json" <<'PY'
import base64, json, sys
from pathlib import Path
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token")
if not token:
    print(data.get("error_description") or data.get("error") or "no subject token", file=sys.stderr)
    sys.exit(1)
Path(sys.argv[2]).write_text(token, encoding="utf-8")
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
keep = {
    "sub": claims.get("sub"),
    "azp": claims.get("azp"),
    "aud": claims.get("aud"),
    "roles": (claims.get("realm_access") or {}).get("roles") or [],
}
json.dump(keep, open(sys.argv[3], "w", encoding="utf-8"))
PY
chmod 600 "$work/subject.jwt"
rm -f "$work/subject.json"

python3 - "$work/subject-claims.json" "$DCR_CLIENT_ID" <<'PY'
import json, sys
claims = json.load(open(sys.argv[1], encoding="utf-8"))
aud = claims.get("aud")
if isinstance(aud, str):
    aud = [aud]
aud = aud or []
if "freedriver-autonomy-mcp" not in aud:
    raise SystemExit("DCR token aud is missing freedriver-autonomy-mcp: " + ",".join(aud))
if "https://app.freedriver.io/autonomy/mcp" not in aud:
    raise SystemExit("DCR token aud is missing the MCP resource: " + ",".join(aud))
if claims.get("azp") != sys.argv[2]:
    raise SystemExit("DCR token azp is " + str(claims.get("azp")))
roles = set(claims.get("roles") or [])
if "dashboard" not in roles or "mcp-user" not in roles:
    raise SystemExit("DCR token roles are " + ",".join(sorted(roles)))
if "portal-admin" in roles:
    raise SystemExit("DCR token must not carry portal-admin: " + ",".join(sorted(roles)))
print("DCR subject token aud", ",".join(aud))
print("DCR subject token roles", ",".join(sorted(roles)))
PY

python3 - "$work/secrets/autonomy-mcp.env" "$work/subject.jwt" "$work/exchange-form" <<'PY'
from pathlib import Path
from urllib.parse import urlencode
import sys
secret = Path(sys.argv[1]).read_text(encoding="utf-8").split("=", 1)[1].strip()
subject = Path(sys.argv[2]).read_text(encoding="utf-8").strip()
Path(sys.argv[3]).write_text(urlencode({
    "grant_type": "urn:ietf:params:oauth:grant-type:token-exchange",
    "client_id": "freedriver-autonomy-mcp",
    "client_secret": secret,
    "subject_token": subject,
    "subject_token_type": "urn:ietf:params:oauth:token-type:access_token",
    "requested_token_type": "urn:ietf:params:oauth:token-type:access_token",
    "audience": "freedriver-api",
}), encoding="utf-8")
PY
chmod 600 "$work/exchange-form"
exchange_status="$(curl -sS -o "$work/exchanged.json" -w '%{http_code}' \
  --data-binary @"$work/exchange-form" \
  "${BASE}/realms/freedriver/protocol/openid-connect/token")"
rm -f "$work/exchange-form" "$work/subject.jwt"
if [[ "$exchange_status" != 200 ]]; then
  echo "token exchange HTTP ${exchange_status}" >&2
  python3 - "$work/exchanged.json" <<'PY' >&2
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print(data.get("error") or "", data.get("error_description") or data)
PY
  exit 1
fi

python3 - "$work/exchanged.json" "$work/subject-claims.json" <<'PY'
import base64, json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
token = data.get("access_token")
if not token:
    print(data.get("error_description") or data.get("error") or "no exchanged token", file=sys.stderr)
    sys.exit(1)
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
subject = json.load(open(sys.argv[2], encoding="utf-8"))
aud = claims.get("aud")
if isinstance(aud, str):
    aud = [aud]
aud = aud or []
roles = set((claims.get("realm_access") or {}).get("roles") or [])
want_roles = set(subject.get("roles") or [])
if claims.get("sub") != subject.get("sub"):
    raise SystemExit("exchanged sub differs from the DCR token")
missing = sorted(want_roles - roles)
if missing:
    raise SystemExit("exchanged token missing roles " + ",".join(missing))
extra = sorted(roles - want_roles)
if extra:
    raise SystemExit("exchanged token has roles the subject token lacks " + ",".join(extra))
if "portal-admin" in roles:
    raise SystemExit("exchanged token carries portal-admin")
if "freedriver-api" not in aud:
    raise SystemExit("exchanged token aud is missing freedriver-api: " + ",".join(aud))
print("exchange ok")
print("exchanged azp", claims.get("azp"))
print("exchanged aud", ",".join(aud))
print("exchanged sub", claims.get("sub"))
print("exchanged roles", ",".join(sorted(roles)))
print("sub matches")
PY
rm -f "$work/exchanged.json"
