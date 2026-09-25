#!/usr/bin/env bash
# Copy the built-in browser flow to browser-freedriver and add phone+SMS
# (freedriver-sms-otp) as an ALTERNATIVE on the copy. The password form stays
# REQUIRED inside the forms Alternative. Creates the phone-sign-in group, makes
# the user profile attribute "phone" admin-only, and sets the freedriver login
# theme. The realm switches to the copy once SMS_OTP_SHARED_SECRET in the
# Keycloak container holds a real value.
#
# Run as root on the VPS after the Keycloak image with the SPI is up.
# Idempotent.
set -euo pipefail

CONTAINER="${KEYCLOAK_CONTAINER:-freedriver-web-keycloak-1}"
TECHOPS_PASS_FILE=/opt/freedriver-secrets/keycloak-techops.pass
KCADM=/opt/keycloak/bin/kcadm.sh
KCADM_CONFIG=/tmp/kcadm-freedriver-sms-otp.config
REALM=freedriver
FLOW=browser-freedriver
PASSWORD_PROVIDER=auth-username-password-form
SMS_PROVIDER=freedriver-sms-otp
GROUP=phone-sign-in
PHONE_ATTRIBUTE=phone
THEME=freedriver
PLACEHOLDER=placeholder-not-a-live-secret
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${ROOT}/scripts/check-sms-otp-flow.py"

if [[ "${1:-}" == "--check-executions" ]]; then
  shift
  exec python3 "$CHECK" "$@"
fi

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo)." >&2
  exit 1
fi

if ! command -v python3 >/dev/null; then
  echo "python3 is required to check the flow JSON." >&2
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

tmp="$(mktemp)"
chmod 600 "$tmp"
cleanup() {
  rm -f "$tmp"
  docker exec "$CONTAINER" rm -f "$KCADM_CONFIG" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# kcadm in Keycloak 26 reads --config after the subcommand.
kcadm() {
  local verb="$1"
  shift
  docker exec "$CONTAINER" "$KCADM" "$verb" --config "$KCADM_CONFIG" "$@"
}

# docker exec copies TECHOPS_PASS from this environment by name.
TECHOPS_PASS="$(tr -d '\n' < "$TECHOPS_PASS_FILE")"
export TECHOPS_PASS
docker exec -e TECHOPS_PASS "$CONTAINER" \
  sh -c "$KCADM config credentials --config $KCADM_CONFIG --server http://127.0.0.1:8080 --realm master --user techops --password \"\$TECHOPS_PASS\"" >/dev/null
unset TECHOPS_PASS

dump_executions() {
  local flow="$1"
  kcadm get "authentication/flows/${flow}/executions" -r "$REALM" > "$tmp"
}

# Confirm the provider is loaded before editing any flow.
kcadm get authentication/authenticator-providers -r "$REALM" > "$tmp"
if ! python3 - "$tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [item.get("id") for item in data]
sys.exit(0 if "freedriver-sms-otp" in ids else 1)
PY
then
  echo "Authenticator ${SMS_PROVIDER} is missing from Keycloak. Rebuild the Keycloak image and re-run." >&2
  exit 1
fi

dump_executions browser
python3 "$CHECK" "$tmp"

if kcadm get authentication/flows -r "$REALM" --fields alias --format csv --noquotes | tr -d '\r' | grep -qx "$FLOW"; then
  echo "Flow ${FLOW} already exists."
else
  kcadm create "authentication/flows/browser/copy" -r "$REALM" -s "newName=${FLOW}" >/dev/null
  echo "Copied browser to ${FLOW}."
fi

dump_executions "$FLOW"
python3 "$CHECK" "$tmp"

sms_count="$(python3 - "$tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
print(sum(1 for e in data if e.get("providerId") == "freedriver-sms-otp"))
PY
)"
if [[ "$sms_count" == "0" ]]; then
  kcadm create "authentication/flows/${FLOW}/executions/execution" -r "$REALM" \
    -s "provider=${SMS_PROVIDER}" >/dev/null
  echo "Added ${SMS_PROVIDER} to ${FLOW}."
  dump_executions "$FLOW"
elif [[ "$sms_count" != "1" ]]; then
  echo "Expected one ${SMS_PROVIDER} execution in ${FLOW}, found ${sms_count}. Fix the flow and re-run." >&2
  exit 1
fi

sms_id="$(python3 - "$tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [e["id"] for e in data if e.get("providerId") == "freedriver-sms-otp"]
if len(ids) != 1:
    sys.exit("SMS execution id missing")
# Print the SMS execution id only.
print(ids[0])
PY
)"

# Priority after every other top-level execution, so cookie SSO and the password
# form come first and phone sign-in is reached with "Try another way".
sms_priority="$(python3 - "$tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
top = [int(e.get("priority") or 0) for e in data
       if e.get("level", 0) == 0 and e.get("providerId") != "freedriver-sms-otp"]
print(max(top, default=0) + 10)
PY
)"

# Only the SMS execution id is updated: ALTERNATIVE, last at the top level.
kcadm update "authentication/flows/${FLOW}/executions" -r "$REALM" \
  -b "{\"id\":\"${sms_id}\",\"requirement\":\"ALTERNATIVE\",\"priority\":${sms_priority}}" >/dev/null

dump_executions "$FLOW"
python3 "$CHECK" --require-sms "$tmp"

# Confirm the password execution is separate from SMS and still REQUIRED.
python3 - "$tmp" "$sms_id" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
sms_id = sys.argv[2]
for execution in data:
    if execution.get("providerId") == "auth-username-password-form" and execution.get("id") == sms_id:
        sys.exit("refusing to treat the password execution as SMS")
    if execution.get("providerId") == "auth-username-password-form" and execution.get("requirement") != "REQUIRED":
        sys.exit("password authenticator is no longer REQUIRED")
PY

# Group that phone sign-in requires; portal-admin adds house users to it.
if [[ -z "$(kcadm get groups -r "$REALM" -q "search=${GROUP}" -q exact=true --fields id --format csv --noquotes)" ]]; then
  kcadm create groups -r "$REALM" -s "name=${GROUP}" >/dev/null
  echo "Created group ${GROUP}."
fi

# User profile attribute "phone", viewed and edited by admins only.
kcadm get users/profile -r "$REALM" > "$tmp"
if python3 - "$tmp" "$PHONE_ATTRIBUTE" <<'PY'
import json, sys
path, name = sys.argv[1], sys.argv[2]
profile = json.load(open(path, encoding="utf-8"))
attributes = profile.setdefault("attributes", [])
wanted = {"view": ["admin"], "edit": ["admin"]}
for attribute in attributes:
    if attribute.get("name") == name:
        if attribute.get("permissions") == wanted:
            sys.exit(1)
        attribute["permissions"] = wanted
        break
else:
    attributes.append({
        "name": name,
        "displayName": "Phone",
        "permissions": wanted,
        "validations": {"pattern": {"pattern": "^\\+[1-9][0-9]{7,14}$", "error-message": "Use international form, e.g. +15555550100."}},
        "multivalued": False,
    })
json.dump(profile, open(path, "w", encoding="utf-8"))
PY
then
  docker exec -i "$CONTAINER" "$KCADM" update --config "$KCADM_CONFIG" users/profile -r "$REALM" -f - < "$tmp" >/dev/null
  echo "User profile attribute ${PHONE_ATTRIBUTE} is admin-only."
fi

# Login theme with the phone sign-in templates.
kcadm update "realms/${REALM}" -s "loginTheme=${THEME}" >/dev/null

# Bind the realm to the copy once the container holds a real secret.
if ! docker exec -e PLACEHOLDER="$PLACEHOLDER" "$CONTAINER" sh -c '
  v=$(printf "%s" "$SMS_OTP_SHARED_SECRET" | sed "s/^[[:space:]]*//;s/[[:space:]]*$//")
  if [ -z "$v" ] || [ "$v" = "$PLACEHOLDER" ]; then
    exit 2
  fi
  exit 0
'; then
  echo "SMS_OTP_SHARED_SECRET is missing or still the placeholder in ${CONTAINER}." >&2
  echo "The realm keeps its current browser flow. Put a real secret in /opt/freedriver-secrets/.env and re-run." >&2
  exit 1
fi

kcadm update "realms/${REALM}" -s "browserFlow=${FLOW}" >/dev/null
echo "Realm ${REALM} browser flow is ${FLOW}. Password stays REQUIRED in the forms Alternative. ${SMS_PROVIDER} is ALTERNATIVE."
