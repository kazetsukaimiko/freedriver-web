#!/usr/bin/env bash
# Throwaway Keycloak 26.3 realm in container freedriver-invite-kc-test.
# Expects 403 for role grants, user create, member password and profile edits,
# impersonation, and client reads, and 204 when adding phone-sign-in membership.
# Prints status codes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CONTAINER=freedriver-invite-kc-test
PORT=18080
BASE="http://127.0.0.1:${PORT}"
IMAGE=quay.io/keycloak/keycloak:26.3

if ! command -v docker >/dev/null; then
  echo "docker is required for the invite service-account checks" >&2
  exit 1
fi
if [[ "${KEYCLOAK_CONTAINER:-}" == "freedriver-web-keycloak-1" || "$CONTAINER" == "freedriver-web-keycloak-1" ]]; then
  echo "refusing to run the invite checks against the production Keycloak container" >&2
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
import json, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
scopes = []
for entry in list(item.get("scopes") or []) + list(item.get("scopesData") or []):
    if isinstance(entry, str):
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
import json, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
names = []
for entry in list(item.get("scopes") or []) + list(item.get("scopesData") or []):
    if isinstance(entry, str):
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
import json, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
names = []
for entry in list(item.get("scopes") or []) + list(item.get("scopesData") or []):
    if isinstance(entry, str):
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
  GRAFANA_ENV_FILE="$work/secrets/grafana.env" \
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
if grep -q 'FREEDRIVER_INSTANCES_CLIENT_SECRET' "$work/secrets/api.secret" "$work/secrets.env" "$work/secrets/invite.env"; then
  echo "instances secret leaked into another file" >&2
  exit 1
fi
if ! grep -q '^FREEDRIVER_GRAFANA_CLIENT_SECRET=.\+' "$work/secrets/grafana.env"; then
  echo "grafana env file is missing a secret value" >&2
  exit 1
fi
if grep -q 'FREEDRIVER_GRAFANA_CLIENT_SECRET' "$work/secrets/api.secret" "$work/secrets.env" "$work/secrets/invite.env" "$work/secrets/instances.env"; then
  echo "grafana secret leaked into another file" >&2
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
  GRAFANA_ENV_FILE="$work/secrets/grafana.env" \
  SECRETS_ENV="$work/secrets.env" \
  bash scripts/provision-keycloak-freedriver.sh
assert_invite_scopes

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
import json, sys
item = json.load(open(sys.argv[1], encoding="utf-8"))
scopes = item.get("scopes") or []
scopes_data = item.get("scopesData") or []
scope_names = sorted({s if isinstance(s, str) else s.get("name") for s in list(scopes) + list(scopes_data) if s})
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

# A failed role check must not replace the phone number.
admin add-roles -r freedriver --uusername kaze --rolename portal-admin >/dev/null
printf 'FREEDRIVER_SEED_PHONE=+15555550999\n' > "$work/secrets.env"
set +e
PROVISION_ALLOW_NON_ROOT=1 \
  KEYCLOAK_CONTAINER="$CONTAINER" \
  TECHOPS_PASS_FILE="$work/techops.pass" \
  SECRET_FILE="$work/secrets/api.secret" \
  INVITE_ENV_FILE="$work/secrets/invite.env" \
  INSTANCES_ENV_FILE="$work/secrets/instances.env" \
  GRAFANA_ENV_FILE="$work/secrets/grafana.env" \
  SECRETS_ENV="$work/secrets.env" \
  bash scripts/provision-keycloak-freedriver.sh >"$work/second.out" 2>&1
second=$?
set -e
if [[ "$second" -eq 0 ]]; then
  echo "second provision should stop when kaze has portal-admin" >&2
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

echo "invite service account refusals ok"
