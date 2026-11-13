#!/usr/bin/env bash
# Start docker-compose.yml plus the CI overlay and assert routing, realm
# wiring, and cross-service calls through Caddy (#484).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-freedriver-web}"
SECRETS="${FREEDRIVER_SMOKE_SECRETS:-/opt/freedriver-secrets}"
STORAGE="${FREEDRIVER_SMOKE_STORAGE:-/opt/freedriver-storage}"
ENV_FILE="${FREEDRIVER_SMOKE_ENV:-$SECRETS/.env}"
LOG_DIR="${FREEDRIVER_SMOKE_LOGS:-/tmp/smoke-logs}"
BUILD_NUMBER="${BUILD_NUMBER:-ci}"
SOURCE_COMMIT="${SOURCE_COMMIT:-ci}"
PHONE="${FREEDRIVER_SEED_PHONE:-+15555550100}"
KEYCLOAK_CONTAINER="${KEYCLOAK_CONTAINER:-${COMPOSE_PROJECT_NAME}-keycloak-1}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f docker-compose.yml -f docker-compose.ci.yml)
GHCR_TAG="$(tr -d '[:space:]' < version.txt)"

mkdir -p "$LOG_DIR"
chmod 700 "$LOG_DIR" || true

as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
  else
    sudo "$@"
  fi
}

cleanup_logs() {
  "${COMPOSE[@]}" ps -a >"$LOG_DIR/ps.txt" 2>&1 || true
  "${COMPOSE[@]}" logs --no-color >"$LOG_DIR/compose.log" 2>&1 || true
}
trap cleanup_logs EXIT

as_root bash -c 'getent group lonewatt-techops >/dev/null || groupadd lonewatt-techops'
as_root mkdir -p \
  "$SECRETS/mqtt-internal" \
  "$SECRETS/mosquitto" \
  "$STORAGE/caddy/data" \
  "$STORAGE/caddy/config" \
  "$STORAGE/keycloak-pg" \
  "$STORAGE/mongodb" \
  "$STORAGE/redis" \
  "$STORAGE/invites" \
  "$STORAGE/sms" \
  "$STORAGE/grafana" \
  "$STORAGE/loki" \
  "$STORAGE/prometheus" \
  "$STORAGE/mosquitto"
as_root chmod -R a+rwX "$SECRETS" "$STORAGE"

hex32() { python3 -c 'import secrets; print(secrets.token_hex(16))'; }
rand_secret() { python3 -c 'import secrets; print(secrets.token_urlsafe(32))'; }

if [[ ! -f "$ENV_FILE" ]]; then
  MCP_SECRET="$(rand_secret)"
  BOOTSTRAP="$(rand_secret)"
  SMS_SECRET="$(rand_secret)"
  cat >"$ENV_FILE" <<EOF
BUILD_NUMBER=${BUILD_NUMBER}
SOURCE_COMMIT=${SOURCE_COMMIT}
QUARKUS_OIDC_CREDENTIALS_SECRET=pending-until-provision
KC_DB_PASSWORD=$(rand_secret)
KC_BOOTSTRAP_ADMIN_PASSWORD=${BOOTSTRAP}
GF_SECURITY_ADMIN_PASSWORD=$(rand_secret)
FREEDRIVER_SMS_DAILY_SEND_CAP=20
SMS_OTP_SHARED_SECRET=${SMS_SECRET}
FREEDRIVER_SEED_PHONE=${PHONE}
FREEDRIVER_MCP_INTERNAL_SECRET=${MCP_SECRET}
FREEDRIVER_AUTONOMY_MCP_ENABLED=true
FREEDRIVER_APPLIANCES_LIVE_COMMANDS=false
EOF
  chmod 600 "$ENV_FILE"
fi

cat >"$SECRETS/twilio-verify.env" <<EOF
TWILIO_ACCOUNT_SID=AC$(hex32)
TWILIO_API_KEY_SID=SK$(hex32)
TWILIO_API_KEY_SECRET=$(rand_secret)
TWILIO_VERIFY_SERVICE_SID=VA$(hex32)
EOF
chmod 600 "$SECRETS/twilio-verify.env"

# Bind-mount sources for the dashboard. A topic line with the template
# placeholder is an ACL with no house UUID, so MetricsMqttForwarder keeps
# the idle MQTT client while mock appliances run.
printf '%s\n' 'topic read freedriver/v1/__INSTANCE_ID__/appliances' >"$SECRETS/mosquitto/acl"
printf '%s\n' 'smoke' >"$SECRETS/mosquitto/api.pass"
if [[ ! -s "$SECRETS/mqtt-internal/ca.crt" ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=smoke" \
    -keyout "$SECRETS/mqtt-internal/ca.key" \
    -out "$SECRETS/mqtt-internal/ca.crt" >/dev/null 2>&1
fi

if ! grep -q 'app.freedriver.io' /etc/hosts; then
  as_root tee -a /etc/hosts >/dev/null <<'EOF'
127.0.0.1 app.freedriver.io auth.freedriver.io grafana.freedriver.io mqtt.freedriver.io www.freedriver.io freedriver.io
EOF
fi

"${COMPOSE[@]}" config --quiet

mapfile -t CHANGED < <(git diff --name-only "${SMOKE_BASE_SHA:-HEAD^}"...HEAD 2>/dev/null || true)
should_build() {
  local service="$1"
  shift
  local path
  for path in "${CHANGED[@]:-}"; do
    for prefix in "$@"; do
      if [[ "$path" == "$prefix"* ]]; then
        return 0
      fi
    done
  done
  return 1
}

pull_or_build() {
  local service="$1"
  local image="ghcr.io/kazetsukaimiko/freedriver-web/${service}:${GHCR_TAG}"
  local local_name="${COMPOSE_PROJECT_NAME}-${service}"
  if docker pull "$image"; then
    docker tag "$image" "$local_name"
    return 0
  fi
  "${COMPOSE[@]}" build "$service"
}

BUILT=()
build_service() {
  local service="$1"
  BUILT+=("$service")
  "${COMPOSE[@]}" build "$service"
}

# Always build the Twilio stub. Pull unchanged product images, or build them
# when this pull request changed their sources.
"${COMPOSE[@]}" build twilio-stub

if should_build dashboard java/dashboard/ java/dashboard-api/ java/dashboard-model/ java/hit-window/; then
  build_service dashboard
else
  pull_or_build dashboard || build_service dashboard
fi
if should_build agent-dispatcher java/agent-dispatcher/ java/agent-dispatcher-api/ java/agent-dispatcher-model/; then
  build_service agent-dispatcher
else
  pull_or_build agent-dispatcher || build_service agent-dispatcher
fi
if should_build autonomy-mcp java/autonomy-mcp/ java/autonomy-mcp-api/; then
  build_service autonomy-mcp
else
  pull_or_build autonomy-mcp || build_service autonomy-mcp
fi
if should_build keycloak keycloak/ sms-client/ sms-api/ sms-model/; then
  build_service keycloak
else
  pull_or_build keycloak || build_service keycloak
fi
# Overlay always points the production sender at the stub, so this image
# has to include freedriver.sms.twilio.allow-stub.
build_service sms

"${COMPOSE[@]}" up -d keycloak-db mongodb redis keycloak sms twilio-stub mosquitto caddy

echo "waiting for Keycloak"
ready=0
for _ in $(seq 1 90); do
  if docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
      --config /tmp/kcadm-smoke-admin.config \
      --server http://127.0.0.1:8080 --realm master --user admin \
      --password "$(python3 - <<'PY'
from pathlib import Path
for line in Path("/opt/freedriver-secrets/.env").read_text().splitlines():
    if line.startswith("KC_BOOTSTRAP_ADMIN_PASSWORD="):
        print(line.split("=",1)[1])
        break
PY
)" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 4
done
if [[ "$ready" != 1 ]]; then
  echo "Keycloak did not become ready" >&2
  docker logs "$KEYCLOAK_CONTAINER" 2>&1 | tail -n 80 >&2 || true
  exit 1
fi

TECHOPS_PASS="$(rand_secret)"
printf '%s\n' "$TECHOPS_PASS" >"$SECRETS/keycloak-techops.pass"
chmod 600 "$SECRETS/keycloak-techops.pass"
export TECHOPS_PASS
docker exec -e TECHOPS_PASS "$KEYCLOAK_CONTAINER" \
  /opt/keycloak/bin/kcadm.sh create users --config /tmp/kcadm-smoke-admin.config \
  -r master -s username=techops -s enabled=true >/dev/null
docker exec -e TECHOPS_PASS "$KEYCLOAK_CONTAINER" \
  sh -c '/opt/keycloak/bin/kcadm.sh set-password --config /tmp/kcadm-smoke-admin.config -r master --username techops --new-password "$TECHOPS_PASS"' \
  >/dev/null
docker exec "$KEYCLOAK_CONTAINER" \
  /opt/keycloak/bin/kcadm.sh add-roles --config /tmp/kcadm-smoke-admin.config \
  -r master --uusername techops --rolename admin >/dev/null
unset TECHOPS_PASS

# sms-otp provision needs the realm; the freedriver provision creates it.
# Create an empty realm first so a fresh CI Keycloak has somewhere to attach
# the browser flow, then run sms-otp, then the rest of the realm.
if ! docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh get realms/freedriver \
    --config /tmp/kcadm-smoke-admin.config >/dev/null 2>&1; then
  docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh create realms \
    --config /tmp/kcadm-smoke-admin.config \
    -s realm=freedriver -s enabled=true -s displayName=Freedriver >/dev/null
fi

as_root env \
  KEYCLOAK_CONTAINER="$KEYCLOAK_CONTAINER" \
  bash "$ROOT/scripts/provision-keycloak-sms-otp.sh"

as_root env \
  KEYCLOAK_CONTAINER="$KEYCLOAK_CONTAINER" \
  TECHOPS_PASS_FILE="$SECRETS/keycloak-techops.pass" \
  SECRET_FILE="$SECRETS/keycloak-freedriver-api.secret" \
  INVITE_ENV_FILE="$SECRETS/keycloak-freedriver-invite.env" \
  INSTANCES_ENV_FILE="$SECRETS/keycloak-freedriver-instances.env" \
  GRAFANA_ENV_FILE="$SECRETS/keycloak-freedriver-grafana.env" \
  MQTT_ENV_FILE="$SECRETS/keycloak-freedriver-mqtt.env" \
  MQTT_OAUTH_DIR="$SECRETS/mosquitto/oauth.d" \
  SECRETS_ENV="$ENV_FILE" \
  bash "$ROOT/scripts/provision-keycloak-freedriver.sh"

# own_secret writes root:lonewatt-techops mode 640. The runner loads those
# throwaway files into compose, so restore world-read after provision.
as_root chmod -R a+rX "$SECRETS"

# kaze is created with username only. The user profile then asks for
# firstName, lastName, and email (VERIFY_PROFILE) after OTP, which leaves
# MCP authorization-code on a 200 page. Fill those fields on the throwaway
# user so phone sign-in finishes.
KAZE_ID="$(docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh get users \
  --config /tmp/kcadm-smoke-admin.config -r freedriver \
  -q username=kaze -q exact=true --fields id --format csv --noquotes \
  | sed '/^id$/d' | awk 'NF { print; exit }')"
if [[ -z "$KAZE_ID" ]]; then
  echo "user kaze missing after provision" >&2
  exit 1
fi
docker exec "$KEYCLOAK_CONTAINER" /opt/keycloak/bin/kcadm.sh update "users/${KAZE_ID}" \
  --config /tmp/kcadm-smoke-admin.config -r freedriver \
  -s firstName=Kaze -s lastName=Smoke \
  -s email=kaze@freedriver.invalid -s emailVerified=true \
  -s 'requiredActions=[]' >/dev/null
unset KAZE_ID

API_SECRET="$(tr -d '\n' <"$SECRETS/keycloak-freedriver-api.secret")"
python3 - "$ENV_FILE" "$API_SECRET" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
secret = sys.argv[2]
lines = []
replaced = False
for line in path.read_text().splitlines():
    if line.startswith("QUARKUS_OIDC_CREDENTIALS_SECRET="):
        lines.append("QUARKUS_OIDC_CREDENTIALS_SECRET=" + secret)
        replaced = True
    else:
        lines.append(line)
if not replaced:
    lines.append("QUARKUS_OIDC_CREDENTIALS_SECRET=" + secret)
path.write_text("\n".join(lines) + "\n")
PY
unset API_SECRET

"${COMPOSE[@]}" up -d dashboard agent-dispatcher autonomy-mcp caddy

echo "waiting for Caddy and the dashboard stamp"
stamp_ok=0
for _ in $(seq 1 90); do
  if curl -skf --resolve app.freedriver.io:443:127.0.0.1 \
      https://app.freedriver.io/api/build | grep -q '"build"'; then
    stamp_ok=1
    break
  fi
  sleep 4
done
if [[ "$stamp_ok" != 1 ]]; then
  echo "dashboard /api/build did not answer" >&2
  exit 1
fi

python3 "$ROOT/scripts/ci-smoke-assert.py"
echo "compose smoke ok"
