#!/usr/bin/env bash
# Provision the freedriver Keycloak realm, confidential client, and roles.
#
# freedriver-api is the Quarkus BFF client only. The client secret must never
# go in the React SPA. This script does not print the secret and will not
# rotate it if /opt/freedriver-secrets/keycloak-freedriver-api.secret exists.
#
# Run as root/sudo on the VPS. Idempotent. Does not set or print user passwords.
set -euo pipefail

CONTAINER="${KEYCLOAK_CONTAINER:-freedriver-web-keycloak-1}"
TECHOPS_PASS_FILE=/opt/freedriver-secrets/keycloak-techops.pass
SECRET_FILE=/opt/freedriver-secrets/keycloak-freedriver-api.secret
KCADM=/opt/keycloak/bin/kcadm.sh
KCADM_CONFIG=/tmp/kcadm-freedriver-techops.config
GROUP=lonewatt-techops
REALM=freedriver

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

if ! getent group "$GROUP" >/dev/null; then
  echo "Group ${GROUP} is missing; create it before re-running." >&2
  exit 1
fi

if [[ ! -f "$TECHOPS_PASS_FILE" ]]; then
  echo "Missing ${TECHOPS_PASS_FILE}" >&2
  exit 1
fi

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -qx true; then
  echo "Container ${CONTAINER} is not running." >&2
  exit 1
fi

cleanup() {
  docker exec "$CONTAINER" rm -f "$KCADM_CONFIG" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kcadm() {
  docker exec "$CONTAINER" "$KCADM" --config "$KCADM_CONFIG" "$@"
}

# Login as master-realm techops. Password is not printed.
docker exec -e TECHOPS_PASS="$(tr -d '\n' < "$TECHOPS_PASS_FILE")" "$CONTAINER" \
  sh -c "$KCADM --config $KCADM_CONFIG config credentials --server http://127.0.0.1:8080 --realm master --user techops --password \"\$TECHOPS_PASS\"" >/dev/null

csv_id() {
  # kcadm csv may include a header row named "id".
  sed '/^id$/d' | tr -d '\r' | awk 'NF { print; exit }'
}

if kcadm get "realms/${REALM}" >/dev/null 2>&1; then
  echo "Realm ${REALM} already exists."
else
  kcadm create realms -s "realm=${REALM}" -s enabled=true -s displayName=Freedriver >/dev/null
  echo "Created realm ${REALM}."
fi

CLIENT_UUID="$(kcadm get clients -r "$REALM" -q clientId=freedriver-api --fields id --format csv --noquotes 2>/dev/null | csv_id || true)"
CREATED_CLIENT=0
if [[ -n "${CLIENT_UUID}" ]]; then
  echo "Client freedriver-api already exists."
else
  # Confidential client for the Quarkus BFF only — never the React SPA.
  kcadm create clients -r "$REALM" \
    -s clientId=freedriver-api \
    -s name='Freedriver API' \
    -s enabled=true \
    -s publicClient=false \
    -s clientAuthenticatorType=client-secret \
    -s standardFlowEnabled=true \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=true \
    -s protocol=openid-connect \
    -s 'description=Quarkus BFF only. Client secret must never go in the React SPA.' \
    -s 'redirectUris=["https://app.freedriver.io/*","http://localhost:8080/*"]' \
    -s 'webOrigins=["https://app.freedriver.io","http://localhost:8080"]' >/dev/null
  CLIENT_UUID="$(kcadm get clients -r "$REALM" -q clientId=freedriver-api --fields id --format csv --noquotes | csv_id)"
  CREATED_CLIENT=1
  echo "Created confidential client freedriver-api (Quarkus BFF only)."
fi

# Keep redirect / origin lists current without rotating the secret.
kcadm update "clients/${CLIENT_UUID}" -r "$REALM" \
  -s publicClient=false \
  -s standardFlowEnabled=true \
  -s serviceAccountsEnabled=true \
  -s 'description=Quarkus BFF only. Client secret must never go in the React SPA.' \
  -s 'redirectUris=["https://app.freedriver.io/*","http://localhost:8080/*"]' \
  -s 'webOrigins=["https://app.freedriver.io","http://localhost:8080"]' >/dev/null

if [[ -e "$SECRET_FILE" ]]; then
  echo "Client secret file already exists; not rotating or rewriting it."
else
  # Fetch the current secret. Never POST/regenerate.
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  kcadm get "clients/${CLIENT_UUID}/client-secret" -r "$REALM" --fields value --format csv --noquotes \
    | sed '/^value$/d' | tr -d '\r' | awk 'NF { print; exit }' > "$tmp"
  if [[ ! -s "$tmp" ]]; then
    rm -f "$tmp"
    echo "Failed to read freedriver-api client secret." >&2
    exit 1
  fi
  chown "root:${GROUP}" "$tmp"
  chmod 640 "$tmp"
  mv "$tmp" "$SECRET_FILE"
  if [[ "$CREATED_CLIENT" -eq 1 ]]; then
    echo "Wrote freedriver-api client secret (not printed)."
  else
    echo "Wrote existing freedriver-api client secret (not printed; not rotated)."
  fi
fi

ensure_role() {
  local name="$1"
  if kcadm get "roles/${name}" -r "$REALM" >/dev/null 2>&1; then
    echo "Role ${name} already exists."
  else
    kcadm create roles -r "$REALM" -s "name=${name}" >/dev/null
    echo "Created role ${name}."
  fi
}

ensure_role dashboard
ensure_role portal-admin

ensure_user() {
  local username="$1"
  local id
  id="$(kcadm get users -r "$REALM" -q "username=${username}" -q exact=true --fields id --format csv --noquotes 2>/dev/null | csv_id || true)"
  if [[ -n "$id" ]]; then
    echo "User ${username} already exists."
    return
  fi
  kcadm create users -r "$REALM" \
    -s "username=${username}" \
    -s enabled=true \
    -s 'requiredActions=["UPDATE_PASSWORD"]' >/dev/null
  echo "Created user ${username} (password unset; UPDATE_PASSWORD required)."
}

ensure_user kazetsukai
ensure_user second

kcadm add-roles -r "$REALM" --uusername kazetsukai --rolename dashboard --rolename portal-admin >/dev/null
kcadm add-roles -r "$REALM" --uusername second --rolename dashboard >/dev/null
echo "Assigned kazetsukai → dashboard + portal-admin; second → dashboard."
