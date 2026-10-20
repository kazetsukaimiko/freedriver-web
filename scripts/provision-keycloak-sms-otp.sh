#!/usr/bin/env bash
# Copy the built-in browser flow to browser-freedriver and insert the signed
# phone-or-username shape. auth-cookie stays the first top-level ALTERNATIVE.
# One top-level ALTERNATIVE subflow holds freedriver-phone-username-router
# (REQUIRED), a phone CONDITIONAL subflow, and a username CONDITIONAL subflow.
# freedriver-sms-otp stays ALTERNATIVE. The password form stays REQUIRED on
# the username path.
#
# An empty or placeholder SMS_OTP_SHARED_SECRET still inserts that shape.
# This script does not write a secret and does not export the realm.
# If any username matches the #155 phone grammar, it records the count and
# does not insert the router.
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
ROUTER_PROVIDER=freedriver-phone-username-router
CONDITION_PROVIDER=freedriver-sms-route-condition
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

work="$(mktemp -d)"
chmod 700 "$work"
tmp="${work}/executions.json"
body="${work}/body.json"
plan="${work}/plan.json"
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

kcadm_stdin() {
  local verb="$1"
  shift
  docker exec -i "$CONTAINER" "$KCADM" "$verb" --config "$KCADM_CONFIG" "$@"
}

dump_executions() {
  local flow="$1"
  kcadm get "authentication/flows/${flow}/executions" -r "$REALM" > "$tmp"
}

safe_id() {
  python3 -c 'import re,sys; value=sys.argv[1];
raise SystemExit(0 if re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value) else 1)' "$1"
}

# docker exec copies TECHOPS_PASS from this environment by name.
TECHOPS_PASS="$(tr -d '\n' < "$TECHOPS_PASS_FILE")"
export TECHOPS_PASS
docker exec -e TECHOPS_PASS "$CONTAINER" \
  sh -c "$KCADM config credentials --config $KCADM_CONFIG --server http://127.0.0.1:8080 --realm master --user techops --password \"\$TECHOPS_PASS\"" >/dev/null
unset TECHOPS_PASS

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
  kcadm_stdin update users/profile -r "$REALM" -f - < "$tmp" >/dev/null
  echo "User profile attribute ${PHONE_ATTRIBUTE} is admin-only."
fi

# The admin API enforces this username rule. It refuses a later username
# that matches the #155 grammar. It does not write a username or a number.
kcadm get users/profile -r "$REALM" > "$tmp"
set +e
python3 "$CHECK" --apply-username-rule "$tmp"
rule_rc=$?
set -e
if [[ "$rule_rc" -eq 0 ]]; then
  kcadm_stdin update users/profile -r "$REALM" -f - < "$tmp" >/dev/null
  echo "User profile refuses a phone-shaped username."
elif [[ "$rule_rc" -ne 3 ]]; then
  exit 1
fi

# Login theme with the phone sign-in templates.
kcadm update "realms/${REALM}" -s "loginTheme=${THEME}" >/dev/null

# Do not enable self-registration.
kcadm get "realms/${REALM}" --fields registrationAllowed > "$tmp"
registration="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("registrationAllowed"))' "$tmp")"
if [[ "$registration" == "True" ]]; then
  kcadm update "realms/${REALM}" -s registrationAllowed=false >/dev/null
  echo "Self-registration is off."
fi

# Count matching usernames without printing them. A positive count stops
# before the router is inserted.
users="${work}/users.json"
echo '[]' > "$users"
first=0
page=100
pages=0
while [[ "$pages" -lt 200 ]]; do
  kcadm get users -r "$REALM" -q "first=${first}" -q "max=${page}" --fields username > "$tmp"
  n="$(python3 -c 'import json,sys; data=json.load(open(sys.argv[1], encoding="utf-8")); print(len(data) if isinstance(data, list) else -1)' "$tmp")"
  if [[ "$n" -lt 0 ]]; then
    echo "User list was not a JSON array. Not inserting the router." >&2
    exit 1
  fi
  python3 - "$users" "$tmp" <<'PY'
import json, sys
dest, src = sys.argv[1], sys.argv[2]
current = json.load(open(dest, encoding="utf-8"))
current.extend(json.load(open(src, encoding="utf-8")))
json.dump(current, open(dest, "w", encoding="utf-8"))
PY
  if [[ "$n" -lt "$page" ]]; then
    break
  fi
  first=$((first + page))
  pages=$((pages + 1))
done
if [[ "$pages" -ge 200 ]]; then
  echo "User list did not end. Not inserting the router." >&2
  exit 1
fi
phone_users="$(python3 "$CHECK" --count-usernames "$users")"
rm -f "$users"
echo "phone-shaped usernames: ${phone_users}"
if [[ "$phone_users" != "0" ]]; then
  echo "Not inserting the router." >&2
  exit 1
fi

kcadm get authentication/authenticator-providers -r "$REALM" > "$tmp"
python3 - "$tmp" "$SMS_PROVIDER" "$ROUTER_PROVIDER" "$CONDITION_PROVIDER" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
ids = {item.get("id") for item in data}
missing = [provider for provider in sys.argv[2:] if provider not in ids]
if missing:
    sys.exit("Authenticator %s is missing from Keycloak. Rebuild the Keycloak image and re-run." % ", ".join(missing))
PY

if kcadm get authentication/flows -r "$REALM" --fields alias --format csv --noquotes | tr -d '\r' | grep -qx "$FLOW"; then
  echo "Flow ${FLOW} already exists."
else
  dump_executions browser
  python3 "$CHECK" --browser-source "$tmp"
  kcadm create "authentication/flows/browser/copy" -r "$REALM" -s "newName=${FLOW}" >/dev/null
  echo "Copied browser to ${FLOW}."
fi

kcadm get "authentication/config-description/${CONDITION_PROVIDER}" -r "$REALM" > "$tmp"
config_key="$(python3 "$CHECK" --select-config-key "$tmp")"

enrich_configs() {
  local list="${work}/config-ids"
  python3 - "$tmp" > "$list" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
for execution in data:
    config_id = execution.get("authenticationConfig")
    if config_id:
        print(execution["id"] + "\t" + config_id)
PY
  local execution_id config_id
  while IFS=$'\t' read -r execution_id config_id; do
    [[ -z "${config_id}" ]] && continue
    safe_id "$config_id"
    kcadm get "authentication/config/${config_id}" -r "$REALM" > "${work}/cfg-${config_id}.json"
  done < "$list"
  python3 - "$tmp" "$work" <<'PY'
import json, sys
from pathlib import Path
path, work = sys.argv[1], Path(sys.argv[2])
data = json.load(open(path, encoding="utf-8"))
for execution in data:
    config_id = execution.get("authenticationConfig")
    if not config_id:
        continue
    loaded = json.load(open(work / ("cfg-" + config_id + ".json"), encoding="utf-8"))
    execution["config"] = loaded.get("config") or {}
    if loaded.get("alias"):
        execution["configAlias"] = loaded["alias"]
json.dump(data, open(path, "w", encoding="utf-8"))
PY
}

apply_plan() {
  local op
  op="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["op"])' "$plan")"
  case "$op" in
    create-flow)
      local parent alias
      parent="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["parent"])' "$plan")"
      alias="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["alias"])' "$plan")"
      python3 - "$plan" "$body" <<'PY'
import json, re, sys
plan = json.load(open(sys.argv[1], encoding="utf-8"))
allowed = {
    "browser-freedriver",
    "freedriver-phone-username",
    "freedriver-phone-path",
    "freedriver-username-path",
    "freedriver-username-2fa",
}
if plan["parent"] not in allowed or plan["alias"] not in allowed:
    sys.exit("refusing flow alias")
if not re.fullmatch(r"[A-Za-z0-9_. -]{1,200}", plan["description"]):
    sys.exit("refusing flow description")
json.dump(
    {"alias": plan["alias"], "type": "basic-flow", "description": plan["description"]},
    open(sys.argv[2], "w", encoding="utf-8"),
)
PY
      kcadm_stdin create "authentication/flows/${parent}/executions/flow" -r "$REALM" -f - < "$body" >/dev/null
      echo "Added subflow ${alias}."
      ;;
    create-execution)
      local parent provider
      parent="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["parent"])' "$plan")"
      provider="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["provider"])' "$plan")"
      python3 -c 'import re,sys; raise SystemExit(0 if re.fullmatch(r"[a-z0-9-]{1,80}", sys.argv[1]) else 1)' "$provider"
      case "$parent" in
        browser-freedriver)
          if [[ "$provider" != "auth-cookie" ]]; then
            echo "refusing execution parent" >&2
            exit 1
          fi
          ;;
        freedriver-phone-username|freedriver-phone-path|freedriver-username-path|freedriver-username-2fa) ;;
        *) echo "refusing execution parent" >&2; exit 1 ;;
      esac
      python3 -c 'import json,sys; json.dump({"provider": sys.argv[1]}, open(sys.argv[2], "w"))' "$provider" "$body"
      kcadm_stdin create "authentication/flows/${parent}/executions/execution" -r "$REALM" -f - < "$body" >/dev/null
      echo "Added execution ${provider}."
      ;;
    set-execution)
      python3 - "$plan" "$body" <<'PY'
import json, sys
plan = json.load(open(sys.argv[1], encoding="utf-8"))
requirement = plan["requirement"]
if requirement not in ("REQUIRED", "ALTERNATIVE", "CONDITIONAL", "DISABLED"):
    sys.exit("refusing requirement")
json.dump(
    {"id": plan["id"], "requirement": requirement, "priority": int(plan["priority"])},
    open(sys.argv[2], "w", encoding="utf-8"),
)
PY
      kcadm_stdin update "authentication/flows/${FLOW}/executions" -r "$REALM" -f - < "$body" >/dev/null
      ;;
    set-config)
      local mode execution_id
      mode="$(python3 -c 'import json,sys; p=json.load(open(sys.argv[1], encoding="utf-8")); print("create" if not p.get("configId") else "update")' "$plan")"
      execution_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["executionId"])' "$plan")"
      safe_id "$execution_id"
      python3 - "$plan" "$body" <<'PY'
import json, sys
plan = json.load(open(sys.argv[1], encoding="utf-8"))
alias = plan["alias"]
if not alias or len(alias) > 200:
    sys.exit("refusing config alias")
body = {"alias": alias, "config": plan["config"]}
if plan.get("configId"):
    body["id"] = plan["configId"]
json.dump(body, open(sys.argv[2], "w", encoding="utf-8"))
PY
      if [[ "$mode" == "create" ]]; then
        kcadm_stdin create "authentication/executions/${execution_id}/config" -r "$REALM" -f - < "$body" >/dev/null
      else
        local config_id
        config_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["configId"])' "$plan")"
        safe_id "$config_id"
        kcadm_stdin update "authentication/config/${config_id}" -r "$REALM" -f - < "$body" >/dev/null
      fi
      echo "Updated an execution configuration."
      ;;
    delete)
      local execution_id
      execution_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["id"])' "$plan")"
      safe_id "$execution_id"
      kcadm delete "authentication/executions/${execution_id}" -r "$REALM"
      echo "Removed an execution outside the phone-username subflow."
      ;;
    *)
      echo "Unknown flow plan ${op}." >&2
      exit 1
      ;;
  esac
}

last_plan=""
repeat=0
finished=0
for _ in $(seq 1 80); do
  dump_executions "$FLOW"
  enrich_configs
  python3 "$CHECK" --plan --flow "$FLOW" --config-key "$config_key" "$tmp" > "$plan"
  op="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["op"])' "$plan")"
  if [[ "$op" == "done" ]]; then
    finished=1
    break
  fi
  if [[ "$op" == "error" ]]; then
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["message"], file=sys.stderr)' "$plan"
    exit 1
  fi
  current="$(cat "$plan")"
  if [[ "$current" == "$last_plan" ]]; then
    repeat=$((repeat + 1))
  else
    repeat=0
    last_plan="$current"
  fi
  if [[ "$repeat" -ge 2 ]]; then
    echo "Flow plan repeated. Not leaving the router half-applied." >&2
    exit 1
  fi
  apply_plan
done
if [[ "$finished" -ne 1 ]]; then
  echo "Flow reshape did not finish." >&2
  exit 1
fi

dump_executions "$FLOW"
enrich_configs
python3 "$CHECK" --config-key "$config_key" "$tmp"

# The shape is inserted whether or not the container holds a real secret.
# The secret is only read inside the container to choose the log line.
secret_off=0
if docker exec -e PLACEHOLDER="$PLACEHOLDER" "$CONTAINER" sh -c '
  v=$(printf "%s" "$SMS_OTP_SHARED_SECRET" | sed "s/^[[:space:]]*//;s/[[:space:]]*$//")
  if [ -z "$v" ] || [ "$v" = "$PLACEHOLDER" ]; then
    exit 2
  fi
  exit 0
'; then
  secret_off=0
else
  secret_rc=$?
  if [[ "$secret_rc" -ne 2 ]]; then
    echo "Could not check SMS_OTP_SHARED_SECRET in ${CONTAINER}." >&2
    exit 1
  fi
  secret_off=1
fi

kcadm update "realms/${REALM}" -s "browserFlow=${FLOW}" >/dev/null
if [[ "$secret_off" -eq 1 ]]; then
  echo "SMS_OTP_SHARED_SECRET is empty or the placeholder. Realm ${REALM} browser flow is ${FLOW}. Entries stay on the password step and no text is sent. No secret was written."
else
  echo "Realm ${REALM} browser flow is ${FLOW}."
fi
