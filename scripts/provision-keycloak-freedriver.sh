#!/usr/bin/env bash
# Provision the freedriver Keycloak realm, confidential client, roles, and
# kaze's phone sign-in user.
#
# freedriver-api is the confidential client of the Quarkus BFF; its secret stays
# on the server with the BFF. When /opt/freedriver-secrets/keycloak-freedriver-api.secret
# is absent, the script writes the current client secret there (root:lonewatt-techops,
# 0640). An existing file and the client secret stay as they are.
#
# User kaze signs in by phone (#169). The script sets its "phone" attribute to
# FREEDRIVER_SEED_PHONE from /opt/freedriver-secrets/.env and makes it a direct
# member of phone-sign-in with the realm role dashboard. Group and role checks
# run before that write. Effective realm roles must be inside dashboard,
# default-roles-freedriver, offline_access, and uma_authorization. Any client
# role stops the script. A +1 number is ten digits whose first digit is 2-9.
# Run scripts/provision-keycloak-sms-otp.sh first; it creates the group and
# the attribute.
#
# The invite service account (#172) is the confidential client freedriver-invite.
# The script writes its secret once to the dashboard env file. Fine-grained
# admin permissions let that account add and remove phone-sign-in members.
# The realm admin creates users. Realm-management roles remain on the realm admin.
#
# Run as root, or as a member of the allowed group, on the VPS. Idempotent. New password users get the
# UPDATE_PASSWORD required action and choose their own password.
set -euo pipefail

CONTAINER="${KEYCLOAK_CONTAINER:-freedriver-web-keycloak-1}"
TECHOPS_PASS_FILE="${TECHOPS_PASS_FILE:-/opt/freedriver-secrets/keycloak-techops.pass}"
SECRET_FILE="${SECRET_FILE:-/opt/freedriver-secrets/keycloak-freedriver-api.secret}"
INVITE_ENV_FILE="${INVITE_ENV_FILE:-/opt/freedriver-secrets/keycloak-freedriver-invite.env}"
SECRETS_ENV="${SECRETS_ENV:-/opt/freedriver-secrets/.env}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KCADM=/opt/keycloak/bin/kcadm.sh
KCADM_CONFIG=/tmp/kcadm-freedriver-techops.config
GROUP=lonewatt-techops
REALM=freedriver
# One exact post-logout redirect for freedriver-api. Not a wildcard.
POST_LOGOUT_REDIRECT_URI='https://app.freedriver.io/'
PHONE_USER=kaze
PHONE_GROUP=phone-sign-in
PHONE_ATTRIBUTE=phone
DEFAULT_ROLES="default-roles-${REALM}"
PHONE_ROLE=dashboard

# Non-root may run only as a member of $GROUP (the group that can read the
# techops password file). Root may always run. PROVISION_ALLOW_NON_ROOT=1
# remains the throwaway-realm escape and still cannot target this production
# container.
in_allowed_group() {
  if id -Gn | tr ' ' '\n' | grep -Fxq -- "$GROUP"; then
    return 0
  fi
  return 1
}

if [[ "${PROVISION_ALLOW_NON_ROOT:-}" == "1" && "$CONTAINER" == "freedriver-web-keycloak-1" ]]; then
  echo "PROVISION_ALLOW_NON_ROOT cannot target the production Keycloak container." >&2
  exit 1
fi
if [[ "$(id -u)" -ne 0 && "${PROVISION_ALLOW_NON_ROOT:-}" != "1" ]] && ! in_allowed_group; then
  echo "Run as root, or as a member of the allowed group." >&2
  exit 1
fi
if [[ "${PROVISION_ALLOW_NON_ROOT:-}" == "1" ]]; then
  echo "PROVISION_ALLOW_NON_ROOT is set for a throwaway realm (container ${CONTAINER})."
fi

if [[ "$(id -u)" -eq 0 ]] && ! getent group "$GROUP" >/dev/null; then
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

work="$(mktemp -d)"
chmod 700 "$work"
cleanup() {
  rm -rf "$work"
  docker exec "$CONTAINER" rm -f "$KCADM_CONFIG" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# kcadm in Keycloak 26 reads --config after the subcommand.
kcadm() {
  local verb="$1"
  shift
  docker exec "$CONTAINER" "$KCADM" "$verb" --config "$KCADM_CONFIG" "$@"
}

# The same, with the request body on stdin.
kcadm_stdin() {
  local verb="$1"
  shift
  docker exec -i "$CONTAINER" "$KCADM" "$verb" --config "$KCADM_CONFIG" "$@"
}

# root:lonewatt-techops mode 640 on the server. The throwaway test is not root.
own_secret() {
  local file="$1"
  if [[ "$(id -u)" -eq 0 ]]; then
    chown "root:${GROUP}" "$file"
    chmod 640 "$file"
  else
    chmod 600 "$file"
  fi
}

# Log in as master-realm techops. docker exec copies TECHOPS_PASS from this
# environment by name.
TECHOPS_PASS="$(tr -d '\n' < "$TECHOPS_PASS_FILE")"
export TECHOPS_PASS
docker exec -e TECHOPS_PASS "$CONTAINER" \
  sh -c "$KCADM config credentials --config $KCADM_CONFIG --server http://127.0.0.1:8080 --realm master --user techops --password \"\$TECHOPS_PASS\"" >/dev/null
unset TECHOPS_PASS

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
  # Confidential client for the Quarkus BFF.
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
    -s "attributes.\"post.logout.redirect.uris\"=${POST_LOGOUT_REDIRECT_URI}" \
    -s 'webOrigins=["https://app.freedriver.io","http://localhost:8080"]' >/dev/null
  CLIENT_UUID="$(kcadm get clients -r "$REALM" -q clientId=freedriver-api --fields id --format csv --noquotes | csv_id)"
  CREATED_CLIENT=1
  echo "Created confidential client freedriver-api (Quarkus BFF only)."
fi

# Keep redirect / origin lists current; the client secret stays the same.
kcadm update "clients/${CLIENT_UUID}" -r "$REALM" \
  -s publicClient=false \
  -s standardFlowEnabled=true \
  -s serviceAccountsEnabled=true \
  -s 'description=Quarkus BFF only. Client secret must never go in the React SPA.' \
  -s 'redirectUris=["https://app.freedriver.io/*","http://localhost:8080/*"]' \
  -s "attributes.\"post.logout.redirect.uris\"=${POST_LOGOUT_REDIRECT_URI}" \
  -s 'webOrigins=["https://app.freedriver.io","http://localhost:8080"]' >/dev/null

# offline_access as a default scope would be requested on every login.
# Optional scope is left as Keycloak created it; the app does not ask for it.
kcadm get "clients/${CLIENT_UUID}/default-client-scopes" -r "$REALM" > "$work/scopes.json"
if ! python3 - "$work/scopes.json" <<'PY'
import json, sys
scopes = json.load(open(sys.argv[1], encoding="utf-8"))
names = {s.get("name") for s in scopes}
sys.exit(0 if "offline_access" not in names else 1)
PY
then
  echo "freedriver-api has offline_access as a default client scope, so it requests offline tokens." >&2
  exit 1
fi
echo "freedriver-api does not request offline tokens."

if [[ -e "$SECRET_FILE" ]]; then
  echo "Client secret file already exists; not rotating or rewriting it."
else
  # Read the current secret with a GET.
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  kcadm get "clients/${CLIENT_UUID}/client-secret" -r "$REALM" --fields value --format csv --noquotes \
    | sed '/^value$/d' | tr -d '\r' | awk 'NF { print; exit }' > "$tmp"
  if [[ ! -s "$tmp" ]]; then
    rm -f "$tmp"
    echo "Failed to read freedriver-api client secret." >&2
    exit 1
  fi
  own_secret "$tmp"
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

# Dispatcher actors. The public client is for Grok and Tesla (authorization code + PKCE).
# Confidential device clients are opt-in: set FREEDRIVER_DEVICE_CLIENTS to a space-separated
# list of client ids. Secrets are written once and never printed. Do not export that list
# on a casual re-run.
ensure_role dispatcher
ensure_role device

ensure_mapper() {
  local client_uuid="$1"
  local name="$2"
  shift 2
  if kcadm get "clients/${client_uuid}/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes \
      | sed '/^name$/d' | grep -qx "$name"; then
    echo "Mapper ${name} already exists."
    return
  fi
  kcadm create "clients/${client_uuid}/protocol-mappers/models" -r "$REALM" \
    -s "name=${name}" \
    -s protocol=openid-connect \
    "$@" >/dev/null
  echo "Created mapper ${name}."
}

DISPATCHER_CLIENT=freedriver-dispatcher
DISPATCHER_UUID="$(kcadm get clients -r "$REALM" -q "clientId=${DISPATCHER_CLIENT}" --fields id --format csv --noquotes 2>/dev/null | csv_id || true)"
if [[ -n "${DISPATCHER_UUID}" ]]; then
  echo "Client ${DISPATCHER_CLIENT} already exists."
else
  kcadm create clients -r "$REALM" \
    -s "clientId=${DISPATCHER_CLIENT}" \
    -s name='Freedriver dispatcher' \
    -s enabled=true \
    -s publicClient=true \
    -s standardFlowEnabled=true \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false \
    -s protocol=openid-connect \
    -s 'description=Public authorization-code + PKCE client for dispatcher actors. Does not mint tokens itself.' \
    -s 'redirectUris=["https://app.freedriver.io/*","http://127.0.0.1/*","http://localhost/*"]' \
    -s 'webOrigins=["https://app.freedriver.io","http://127.0.0.1","http://localhost"]' \
    -s 'attributes."pkce.code.challenge.method"=S256' >/dev/null
  DISPATCHER_UUID="$(kcadm get clients -r "$REALM" -q "clientId=${DISPATCHER_CLIENT}" --fields id --format csv --noquotes | csv_id)"
  echo "Created public client ${DISPATCHER_CLIENT}."
fi

kcadm update "clients/${DISPATCHER_UUID}" -r "$REALM" \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=false \
  -s 'redirectUris=["https://app.freedriver.io/*","http://127.0.0.1/*","http://localhost/*"]' \
  -s 'webOrigins=["https://app.freedriver.io","http://127.0.0.1","http://localhost"]' \
  -s 'attributes."pkce.code.challenge.method"=S256' >/dev/null

ensure_mapper "$DISPATCHER_UUID" actor-dispatcher \
  -s protocolMapper=oidc-hardcoded-claim-mapper \
  -s 'config."claim.name"=actor' \
  -s 'config."claim.value"=dispatcher' \
  -s 'config."jsonType.label"=String' \
  -s 'config."id.token.claim"=true' \
  -s 'config."access.token.claim"=true' \
  -s 'config."userinfo.token.claim"=false'
ensure_mapper "$DISPATCHER_UUID" role-dispatcher \
  -s protocolMapper=oidc-hardcoded-role-mapper \
  -s 'config."role"=dispatcher'

ensure_device_client() {
  local id="$1"
  local uuid secret_file tmp
  if [[ ! "$id" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
    echo "Refusing device client id ${id}" >&2
    exit 1
  fi
  uuid="$(kcadm get clients -r "$REALM" -q "clientId=${id}" --fields id --format csv --noquotes 2>/dev/null | csv_id || true)"
  if [[ -n "$uuid" ]]; then
    echo "Device client ${id} already exists."
  else
    kcadm create clients -r "$REALM" \
      -s "clientId=${id}" \
      -s "name=Freedriver device ${id}" \
      -s enabled=true \
      -s publicClient=false \
      -s clientAuthenticatorType=client-secret \
      -s standardFlowEnabled=false \
      -s implicitFlowEnabled=false \
      -s directAccessGrantsEnabled=false \
      -s serviceAccountsEnabled=true \
      -s protocol=openid-connect \
      -s "description=Confidential client-credentials client for device ${id}." >/dev/null
    uuid="$(kcadm get clients -r "$REALM" -q "clientId=${id}" --fields id --format csv --noquotes | csv_id)"
    echo "Created device client ${id}."
  fi
  kcadm update "clients/${uuid}" -r "$REALM" \
    -s publicClient=false \
    -s serviceAccountsEnabled=true \
    -s standardFlowEnabled=false \
    -s directAccessGrantsEnabled=false >/dev/null
  kcadm add-roles -r "$REALM" --uusername "service-account-${id}" --rolename device >/dev/null
  ensure_mapper "$uuid" actor-device \
    -s protocolMapper=oidc-hardcoded-claim-mapper \
    -s 'config."claim.name"=actor' \
    -s 'config."claim.value"=device' \
    -s 'config."jsonType.label"=String' \
    -s 'config."access.token.claim"=true' \
    -s 'config."id.token.claim"=false' \
    -s 'config."userinfo.token.claim"=false'
  ensure_mapper "$uuid" device-id \
    -s protocolMapper=oidc-hardcoded-claim-mapper \
    -s 'config."claim.name"=device_id' \
    -s "config.\"claim.value\"=${id}" \
    -s 'config."jsonType.label"=String' \
    -s 'config."access.token.claim"=true' \
    -s 'config."id.token.claim"=false' \
    -s 'config."userinfo.token.claim"=false'
  ensure_mapper "$uuid" role-device \
    -s protocolMapper=oidc-hardcoded-role-mapper \
    -s 'config."role"=device'
  secret_file="/opt/freedriver-secrets/keycloak-device-${id}.secret"
  if [[ -e "$secret_file" ]]; then
    echo "Device secret file for ${id} already exists; not rotating or rewriting it."
    return
  fi
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  kcadm get "clients/${uuid}/client-secret" -r "$REALM" --fields value --format csv --noquotes \
    | sed '/^value$/d' | tr -d '\r' | awk 'NF { print; exit }' > "$tmp"
  if [[ ! -s "$tmp" ]]; then
    rm -f "$tmp"
    echo "Failed to read device client secret for ${id}." >&2
    exit 1
  fi
  own_secret "$tmp"
  mv "$tmp" "$secret_file"
  echo "Wrote device client secret for ${id} (not printed)."
}

if [[ -n "${FREEDRIVER_DEVICE_CLIENTS:-}" ]]; then
  for device_id in ${FREEDRIVER_DEVICE_CLIENTS}; do
    ensure_device_client "$device_id"
  done
else
  echo "FREEDRIVER_DEVICE_CLIENTS is unset; no device clients created."
fi

lookup_phone_group() {
  kcadm get groups -r "$REALM" -q "search=${PHONE_GROUP}" -q exact=true > "$work/groups.json"
  python3 - "$work/groups.json" "$PHONE_GROUP" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [g["id"] for g in groups if g.get("name") == sys.argv[2] and not g.get("parentId")]
print(ids[0] if len(ids) == 1 else "")
PY
}

require_phone_group() {
  group_id="$(lookup_phone_group)"
  if [[ -z "$group_id" ]]; then
    echo "Top-level group ${PHONE_GROUP} is missing. Run scripts/provision-keycloak-sms-otp.sh first." >&2
    exit 1
  fi
}

# Names only. A missing name is not an error; an extra name is.
phone_roles_allowed() {
  local user_id="$1"
  kcadm get "users/${user_id}/role-mappings/realm/composite" -r "$REALM" > "$work/realm-composite.json"
  kcadm get "users/${user_id}/role-mappings" -r "$REALM" > "$work/roles.json"
  python3 "$SCRIPT_DIR/check-phone-user-roles.py" "$work/realm-composite.json" "$work/roles.json" "$REALM"
}

authz_has_name() {
  local path="$1" wanted="$2"
  if ! kcadm get "$path" -r "$REALM" > "$work/authz-list.json" 2>/dev/null; then
    return 1
  fi
  python3 - "$work/authz-list.json" "$wanted" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("policies") or data.get("permissions") or data.get("results") or []
names = {item.get("name") for item in data if isinstance(item, dict)}
sys.exit(0 if sys.argv[2] in names else 1)
PY
}

# Id of a scope permission with this name, or empty when it is missing.
scope_permission_id() {
  local path="$1" wanted="$2"
  if ! kcadm get "$path" -r "$REALM" -q max=1000 > "$work/scope-perms.json" 2>/dev/null; then
    return 0
  fi
  python3 - "$work/scope-perms.json" "$wanted" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if isinstance(data, dict):
    data = data.get("permissions") or data.get("results") or []
for item in data:
    if isinstance(item, dict) and item.get("name") == sys.argv[2] and item.get("id"):
        print(item["id"])
        break
PY
}

# Create the scope permission, or replace its scopes, resources, and policies.
apply_scope_permission() {
  local path="$1" name="$2" body="$3" perm_id
  perm_id="$(scope_permission_id "$path" "$name" | tr -d '\r')"
  if [[ -z "$perm_id" ]]; then
    kcadm_stdin create "$path" -r "$REALM" -f - < "$body" >/dev/null
    echo "Created permission ${name}."
    return 0
  fi
  python3 - "$body" "$perm_id" > "$work/perm-update.json" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
doc["id"] = sys.argv[2]
json.dump(doc, sys.stdout)
PY
  kcadm_stdin update "${path}/${perm_id}" -r "$REALM" -f - < "$work/perm-update.json" >/dev/null
  echo "Updated permission ${name}."
}

# Invite service account (#172). The secret file is for the dashboard container.
# Realm-management roles remain on the realm admin.
ensure_invite_service_account() {
  local client_uuid sa_user policy_path perm_names admin_client secret tmp
  require_phone_group
  kcadm update "realms/${REALM}" -s adminPermissionsEnabled=true >/dev/null
  client_uuid="$(kcadm get clients -r "$REALM" -q clientId=freedriver-invite --fields id --format csv --noquotes 2>/dev/null | csv_id || true)"
  if [[ -z "$client_uuid" ]]; then
    kcadm create clients -r "$REALM" \
      -s clientId=freedriver-invite \
      -s name='Freedriver invite' \
      -s enabled=true \
      -s publicClient=false \
      -s clientAuthenticatorType=client-secret \
      -s standardFlowEnabled=false \
      -s implicitFlowEnabled=false \
      -s directAccessGrantsEnabled=false \
      -s serviceAccountsEnabled=true \
      -s protocol=openid-connect \
      -s 'description=Service account for phone-sign-in membership. Secret stays on the dashboard container.' >/dev/null
    client_uuid="$(kcadm get clients -r "$REALM" -q clientId=freedriver-invite --fields id --format csv --noquotes | csv_id)"
    echo "Created confidential client freedriver-invite."
  else
    echo "Client freedriver-invite already exists."
  fi
  kcadm update "clients/${client_uuid}" -r "$REALM" \
    -s publicClient=false \
    -s standardFlowEnabled=false \
    -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=true \
    -s fullScopeAllowed=false \
    -s 'description=Service account for phone-sign-in membership. Secret stays on the dashboard container.' >/dev/null
  sa_user="$(kcadm get "clients/${client_uuid}/service-account-user" -r "$REALM" --fields id --format csv --noquotes | csv_id)"
  kcadm get "users/${sa_user}/role-mappings" -r "$REALM" > "$work/invite-roles.json"
  python3 - "$work/invite-roles.json" <<'PY' > "$work/invite-drop-roles"
import json, sys
mappings = json.load(open(sys.argv[1], encoding="utf-8"))
client = (mappings.get("clientMappings") or {}).get("realm-management") or {}
for role in client.get("mappings") or []:
    name = role.get("name")
    if name:
        print(name)
PY
  if [[ -s "$work/invite-drop-roles" ]]; then
    while IFS= read -r role_name; do
      kcadm remove-roles -r "$REALM" --uid "$sa_user" --cclientid realm-management --rolename "$role_name" >/dev/null
      echo "Removed realm-management role ${role_name} from the invite service account."
    done < "$work/invite-drop-roles"
  fi
  if [[ -e "$INVITE_ENV_FILE" ]]; then
    echo "Invite client env file already exists; not rotating or rewriting it."
  else
    tmp="$(mktemp)"
    chmod 600 "$tmp"
    secret="$(kcadm get "clients/${client_uuid}/client-secret" -r "$REALM" --fields value --format csv --noquotes \
      | sed '/^value$/d' | tr -d '\r' | awk 'NF { print; exit }')"
    if [[ -z "$secret" || "$secret" == *" "* || "$secret" == *$'\n'* ]]; then
      rm -f "$tmp"
      unset secret
      echo "Failed to read freedriver-invite client secret." >&2
      exit 1
    fi
    printf 'FREEDRIVER_INVITE_CLIENT_SECRET=%s\n' "$secret" > "$tmp"
    unset secret
    own_secret "$tmp"
    mv "$tmp" "$INVITE_ENV_FILE"
    echo "Wrote invite client secret for the dashboard container (not printed)."
  fi
  admin_client="$(kcadm get clients -r "$REALM" -q clientId=admin-permissions --fields id --format csv --noquotes | csv_id)"
  policy_path="clients/${admin_client}/authz/resource-server"
  # Keycloak evaluates a service-account token as the client. The identity id is
  # the client UUID, so the permission policy is a client policy.
  if ! authz_has_name "${policy_path}/policy" freedriver-invite-sa; then
    python3 - "$client_uuid" > "$work/policy.json" <<'PY'
import json, sys
json.dump({"name": "freedriver-invite-sa", "type": "client", "logic": "POSITIVE",
           "decisionStrategy": "UNANIMOUS", "clients": [sys.argv[1]]}, sys.stdout)
PY
    kcadm_stdin create "${policy_path}/policy/client" -r "$REALM" -f - < "$work/policy.json" >/dev/null
    echo "Created invite service-account policy."
  fi
  python3 - "$group_id" > "$work/perm-group.json" <<'PY'
import json, sys
json.dump({
    "name": "invite-phone-sign-in-membership",
    "type": "scope",
    "logic": "POSITIVE",
    "decisionStrategy": "UNANIMOUS",
    "resourceType": "Groups",
    "resources": [sys.argv[1]],
    "scopes": ["manage-membership"],
    "policies": ["freedriver-invite-sa"],
}, sys.stdout)
PY
  apply_scope_permission "${policy_path}/permission/scope" invite-phone-sign-in-membership "$work/perm-group.json"
  python3 > "$work/perm-users.json" <<'PY'
import json, sys
json.dump({
    "name": "invite-user-group-membership",
    "type": "scope",
    "logic": "POSITIVE",
    "decisionStrategy": "UNANIMOUS",
    "resourceType": "Users",
    "scopes": ["manage-group-membership"],
    "policies": ["freedriver-invite-sa"],
}, sys.stdout)
PY
  apply_scope_permission "${policy_path}/permission/scope" invite-user-group-membership "$work/perm-users.json"
  echo "Invite service account manages phone-sign-in membership."
}

ensure_invite_service_account

# Phone sign-in user kaze (#169). Checks run before the phone attribute is written.
seed_phone="$(sed -n 's/^FREEDRIVER_SEED_PHONE=//p' "$SECRETS_ENV" 2>/dev/null | tail -n 1 \
  | tr -d '\r' | sed "s/^[[:space:]]*//;s/[[:space:]]*\$//;s/^[\"']//;s/[\"']\$//" | tr -d ' ().-')"
if [[ -z "$seed_phone" ]]; then
  echo "FREEDRIVER_SEED_PHONE is empty in ${SECRETS_ENV}; user ${PHONE_USER} is unchanged."
  exit 0
fi
if ! [[ "$seed_phone" =~ ^\+1[2-9][0-9]{9}$ ]]; then
  echo "FREEDRIVER_SEED_PHONE in ${SECRETS_ENV} is not a +1 number like +15555550100." >&2
  exit 1
fi

require_phone_group

kcadm get users/profile -r "$REALM" > "$work/profile.json"
if ! python3 - "$work/profile.json" "$PHONE_ATTRIBUTE" <<'PY'
import json, sys
profile = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if any(a.get("name") == sys.argv[2] for a in profile.get("attributes", [])) else 1)
PY
then
  echo "User profile attribute ${PHONE_ATTRIBUTE} is missing. Run scripts/provision-keycloak-sms-otp.sh first." >&2
  exit 1
fi

# Every user with attributes, read once, so the number stays out of command lines.
kcadm get users -r "$REALM" -q briefRepresentation=false -q max=100000 > "$work/users.json"
export SEED_PHONE="$seed_phone"
phone_state="$(python3 - "$work/users.json" "$PHONE_USER" "$PHONE_ATTRIBUTE" <<'PY'
import json, os, re, sys
users, name, attr = json.load(open(sys.argv[1], encoding="utf-8")), sys.argv[2], sys.argv[3]
seed = os.environ["SEED_PHONE"]
norm = lambda v: re.sub(r"[\s().-]", "", v or "")
match = [u for u in users if u.get("username") == name]
for u in users:
    if u.get("username") != name and any(norm(v) == seed for v in (u.get("attributes") or {}).get(attr, [])):
        print("taken")
        sys.exit(0)
if not match:
    print("missing")
elif (match[0].get("attributes") or {}).get(attr) == [seed]:
    print("same " + match[0]["id"])
else:
    print("differs " + match[0]["id"])
PY
)"

case "$phone_state" in
  taken)
    unset SEED_PHONE
    echo "Another ${REALM} user already has this ${PHONE_ATTRIBUTE}. Clear it there and re-run." >&2
    exit 1
    ;;
  missing)
    # No phone attribute yet, so a failed group or role check leaves no number.
    python3 - "$PHONE_USER" > "$work/body.json" <<'PY'
import json, sys
json.dump({"username": sys.argv[1], "enabled": True}, sys.stdout)
PY
    kcadm_stdin create users -r "$REALM" -f - < "$work/body.json" >/dev/null
    user_id="$(kcadm get users -r "$REALM" -q "username=${PHONE_USER}" -q exact=true --fields id --format csv --noquotes | csv_id)"
    echo "Created user ${PHONE_USER} without ${PHONE_ATTRIBUTE}."
    ;;
  differs\ *|same\ *)
    user_id="${phone_state#* }"
    ;;
  *)
    unset SEED_PHONE
    echo "Could not read ${REALM} users." >&2
    exit 1
    ;;
esac

kcadm get "users/${user_id}/groups" -r "$REALM" > "$work/member.json"
member_state="$(python3 - "$work/member.json" "$group_id" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1], encoding="utf-8"))
ids = {g["id"] for g in groups}
if ids - {sys.argv[2]}:
    print("other")
elif sys.argv[2] in ids:
    print("member")
else:
    print("none")
PY
)"
if [[ "$member_state" == "other" ]]; then
  unset SEED_PHONE
  echo "User ${PHONE_USER} belongs to a group other than ${PHONE_GROUP}. Remove it and re-run." >&2
  exit 1
fi

# phone roles allow-list before the phone attribute
if ! phone_roles_allowed "$user_id"; then
  unset SEED_PHONE
  echo "User ${PHONE_USER} has a realm role outside the allow-list or a client role. The phone attribute was not changed." >&2
  exit 1
fi

if [[ "$phone_state" == missing || "$phone_state" == differs\ * ]]; then
  kcadm get "users/${user_id}" -r "$REALM" > "$work/user.json"
  python3 - "$work/user.json" "$PHONE_ATTRIBUTE" > "$work/body.json" <<'PY'
import json, os, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user.setdefault("attributes", {})[sys.argv[2]] = [os.environ["SEED_PHONE"]]
json.dump(user, sys.stdout)
PY
  kcadm_stdin update "users/${user_id}" -r "$REALM" -f - < "$work/body.json" >/dev/null
  echo "Set ${PHONE_ATTRIBUTE} on user ${PHONE_USER} (number not printed)."
else
  echo "User ${PHONE_USER} already has this ${PHONE_ATTRIBUTE}."
fi
unset SEED_PHONE
rm -f "$work/users.json" "$work/body.json" "$work/user.json"

if [[ "$member_state" == "none" ]]; then
  kcadm update "users/${user_id}/groups/${group_id}" -r "$REALM" \
    -s "realm=${REALM}" -s "userId=${user_id}" -s "groupId=${group_id}" -n >/dev/null
  echo "Added user ${PHONE_USER} to ${PHONE_GROUP}."
else
  echo "User ${PHONE_USER} is already in ${PHONE_GROUP}."
fi

kcadm add-roles -r "$REALM" --uid "$user_id" --rolename "$PHONE_ROLE" >/dev/null

if ! phone_roles_allowed "$user_id"; then
  kcadm get "users/${user_id}" -r "$REALM" > "$work/user.json"
  python3 - "$work/user.json" "$PHONE_ATTRIBUTE" > "$work/body.json" <<'PY'
import json, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
attrs = user.get("attributes") or {}
attrs.pop(sys.argv[2], None)
user["attributes"] = attrs
json.dump(user, sys.stdout)
PY
  kcadm_stdin update "users/${user_id}" -r "$REALM" -f - < "$work/body.json" >/dev/null
  echo "User ${PHONE_USER} has a realm role outside the allow-list or a client role. The phone attribute was removed." >&2
  exit 1
fi
echo "User ${PHONE_USER} has ${PHONE_GROUP}, ${PHONE_ROLE}, and no realm role outside the allow-list."
