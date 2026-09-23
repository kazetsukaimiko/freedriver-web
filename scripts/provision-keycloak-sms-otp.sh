#!/usr/bin/env bash
# Copy the freedriver browser flow and add phone+SMS as an Alternative.
#
# Does not disable password. Does not set SMS to REQUIRED. Does not print
# SMS_OTP_SHARED_SECRET. Does not bind the new flow while that secret is
# missing or still the git placeholder. Does not touch direct grant.
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

kcadm() {
  docker exec "$CONTAINER" "$KCADM" --config "$KCADM_CONFIG" "$@"
}

docker exec -e TECHOPS_PASS="$(tr -d '\n' < "$TECHOPS_PASS_FILE")" "$CONTAINER" \
  sh -c "$KCADM --config $KCADM_CONFIG config credentials --server http://127.0.0.1:8080 --realm master --user techops --password \"\$TECHOPS_PASS\"" >/dev/null

dump_executions() {
  local flow="$1"
  kcadm get "authentication/flows/${flow}/executions" -r "$REALM" > "$tmp"
}

# Provider must already be in the image. Do not edit flows if it is absent.
kcadm get authentication/authenticator-providers -r "$REALM" > "$tmp"
if ! python3 - "$tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [item.get("id") for item in data]
sys.exit(0 if "freedriver-sms-otp" in ids else 1)
PY
then
  echo "Authenticator ${SMS_PROVIDER} is not loaded. Rebuild the Keycloak image; password flow was not changed." >&2
  exit 1
fi

dump_executions browser
python3 "$CHECK" "$tmp"

if kcadm get "authentication/flows/${FLOW}" -r "$REALM" >/dev/null 2>&1; then
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
  echo "Expected one ${SMS_PROVIDER} execution, found ${sms_count}. Password flow binding was not changed." >&2
  exit 1
fi

sms_id="$(python3 - "$tmp" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
ids = [e["id"] for e in data if e.get("providerId") == "freedriver-sms-otp"]
if len(ids) != 1:
    sys.exit("SMS execution id missing")
# Refuse to print or update any other execution.
print(ids[0])
PY
)"

# Only the SMS execution id is updated, and only to ALTERNATIVE.
kcadm update "authentication/flows/${FLOW}/executions" -r "$REALM" \
  -b "{\"id\":\"${sms_id}\",\"requirement\":\"ALTERNATIVE\"}" >/dev/null

dump_executions "$FLOW"
python3 "$CHECK" --require-sms "$tmp"

# Confirm the password execution id was not the one we updated.
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

# Missing or placeholder secret: leave the realm browser flow where it is.
if ! docker exec -e PLACEHOLDER="$PLACEHOLDER" "$CONTAINER" sh -c '
  v=$(printf "%s" "$SMS_OTP_SHARED_SECRET" | sed "s/^[[:space:]]*//;s/[[:space:]]*$//")
  if [ -z "$v" ] || [ "$v" = "$PLACEHOLDER" ]; then
    exit 2
  fi
  exit 0
'; then
  echo "SMS_OTP_SHARED_SECRET is missing or still the placeholder in ${CONTAINER}." >&2
  echo "Password login was left on the current browser flow. Put a real secret in /opt/freedriver-secrets/.env (not the git example) and re-run." >&2
  exit 1
fi

kcadm update "realms/${REALM}" -s "browserFlow=${FLOW}" >/dev/null
echo "Realm ${REALM} browser flow is ${FLOW}. Password stays REQUIRED in the forms Alternative. ${SMS_PROVIDER} is ALTERNATIVE."
echo "Direct grant was not changed. Secret was not printed."
