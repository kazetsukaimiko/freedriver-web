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
# member of phone-sign-in with the realm role dashboard. It checks that kaze has
# no other group and no role beyond dashboard and default-roles-freedriver, and
# stops if it finds one. The number is
# never printed. Run scripts/provision-keycloak-sms-otp.sh first; it creates
# the group and the attribute.
#
# Run as root/sudo on the VPS. Idempotent. New password users get the
# UPDATE_PASSWORD required action and choose their own password.
set -euo pipefail

CONTAINER="${KEYCLOAK_CONTAINER:-freedriver-web-keycloak-1}"
TECHOPS_PASS_FILE="${TECHOPS_PASS_FILE:-/opt/freedriver-secrets/keycloak-techops.pass}"
SECRET_FILE="${SECRET_FILE:-/opt/freedriver-secrets/keycloak-freedriver-api.secret}"
SECRETS_ENV="${SECRETS_ENV:-/opt/freedriver-secrets/.env}"
KCADM=/opt/keycloak/bin/kcadm.sh
KCADM_CONFIG=/tmp/kcadm-freedriver-techops.config
GROUP=lonewatt-techops
REALM=freedriver
PHONE_USER=kaze
PHONE_GROUP=phone-sign-in
PHONE_ATTRIBUTE=phone
DEFAULT_ROLES="default-roles-${REALM}"
PHONE_ROLE=dashboard

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
  -s 'webOrigins=["https://app.freedriver.io","http://localhost:8080"]' >/dev/null

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

# Phone sign-in user kaze (#169).
seed_phone="$(sed -n 's/^FREEDRIVER_SEED_PHONE=//p' "$SECRETS_ENV" 2>/dev/null | tail -n 1 \
  | tr -d '\r' | sed "s/^[[:space:]]*//;s/[[:space:]]*\$//;s/^[\"']//;s/[\"']\$//" | tr -d ' ().-')"
if [[ -z "$seed_phone" ]]; then
  echo "FREEDRIVER_SEED_PHONE is empty in ${SECRETS_ENV}; user ${PHONE_USER} is unchanged."
  exit 0
fi
if ! [[ "$seed_phone" =~ ^\+[1-9][0-9]{7,14}$ ]]; then
  echo "FREEDRIVER_SEED_PHONE in ${SECRETS_ENV} is not an international number like +15555550100." >&2
  exit 1
fi

kcadm get groups -r "$REALM" -q "search=${PHONE_GROUP}" -q exact=true > "$work/groups.json"
group_id="$(python3 - "$work/groups.json" "$PHONE_GROUP" <<'PY'
import json, sys
groups = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [g["id"] for g in groups if g.get("name") == sys.argv[2] and not g.get("parentId")]
print(ids[0] if len(ids) == 1 else "")
PY
)"
if [[ -z "$group_id" ]]; then
  echo "Top-level group ${PHONE_GROUP} is missing. Run scripts/provision-keycloak-sms-otp.sh first." >&2
  exit 1
fi

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
    python3 - "$PHONE_USER" "$PHONE_ATTRIBUTE" > "$work/body.json" <<'PY'
import json, os, sys
json.dump({"username": sys.argv[1], "enabled": True,
           "attributes": {sys.argv[2]: [os.environ["SEED_PHONE"]]}}, sys.stdout)
PY
    kcadm_stdin create users -r "$REALM" -f - < "$work/body.json" >/dev/null
    echo "Created user ${PHONE_USER} with ${PHONE_ATTRIBUTE} set (number not printed)."
    ;;
  differs\ *)
    user_id="${phone_state#differs }"
    kcadm get "users/${user_id}" -r "$REALM" > "$work/user.json"
    python3 - "$work/user.json" "$PHONE_ATTRIBUTE" > "$work/body.json" <<'PY'
import json, os, sys
user = json.load(open(sys.argv[1], encoding="utf-8"))
user.setdefault("attributes", {})[sys.argv[2]] = [os.environ["SEED_PHONE"]]
json.dump(user, sys.stdout)
PY
    kcadm_stdin update "users/${user_id}" -r "$REALM" -f - < "$work/body.json" >/dev/null
    echo "Updated ${PHONE_ATTRIBUTE} on user ${PHONE_USER} (number not printed)."
    ;;
  same\ *)
    echo "User ${PHONE_USER} already has this ${PHONE_ATTRIBUTE}."
    ;;
  *)
    unset SEED_PHONE
    echo "Could not read ${REALM} users." >&2
    exit 1
    ;;
esac
unset SEED_PHONE
rm -f "$work/users.json" "$work/body.json" "$work/user.json"

user_id="$(kcadm get users -r "$REALM" -q "username=${PHONE_USER}" -q exact=true --fields id --format csv --noquotes | csv_id)"

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
case "$member_state" in
  other)
    echo "User ${PHONE_USER} belongs to a group other than ${PHONE_GROUP}. Remove it and re-run." >&2
    exit 1
    ;;
  none)
    kcadm update "users/${user_id}/groups/${group_id}" -r "$REALM" \
      -s "realm=${REALM}" -s "userId=${user_id}" -s "groupId=${group_id}" -n >/dev/null
    echo "Added user ${PHONE_USER} to ${PHONE_GROUP}."
    ;;
  member)
    echo "User ${PHONE_USER} is already in ${PHONE_GROUP}."
    ;;
esac

kcadm add-roles -r "$REALM" --uid "$user_id" --rolename "$PHONE_ROLE" >/dev/null

kcadm get "users/${user_id}/role-mappings" -r "$REALM" > "$work/roles.json"
if ! python3 - "$work/roles.json" "$PHONE_ROLE" "$DEFAULT_ROLES" <<'PY'
import json, sys
mappings = json.load(open(sys.argv[1], encoding="utf-8"))
realm = {r.get("name") for r in mappings.get("realmMappings", [])}
sys.exit(0 if sys.argv[2] in realm and realm <= {sys.argv[2], sys.argv[3]}
         and not mappings.get("clientMappings") else 1)
PY
then
  echo "User ${PHONE_USER} has a role beyond ${PHONE_ROLE} and ${DEFAULT_ROLES}. Remove it and re-run." >&2
  exit 1
fi
echo "User ${PHONE_USER} has ${PHONE_GROUP}, ${PHONE_ROLE}, and no other role beyond ${DEFAULT_ROLES}."
