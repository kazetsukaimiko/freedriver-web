#!/usr/bin/env bash
# Provision Mosquitto MQTTS secrets, storage, and instance-scoped ACLs on the VPS.
# Full provision runs as root/sudo. --acl-only runs as any user and is the
# mode CI uses, writing to a temp file. Idempotent: existing pass and cert
# files stay in place, and output reports password files by name.
#
# Git keeps mosquitto/acl.template. The live ACL is
# /opt/freedriver-secrets/mosquitto/acl (secrets mount ->
# /mosquitto/config/secrets/acl). Apply substitutes the house instanceId for
# __INSTANCE_ID__ in the template's topic lines:
#   INSTANCE_ID=<uuid> ./scripts/provision-mosquitto.sh
#   ./scripts/provision-mosquitto.sh --instance-id <uuid>
# Long-term, freedriver-web owns minting instanceId. Techops runs the
# first-house apply with the locked first-house instanceId. Techops restarts
# Mosquitto afterwards to load the ACL.
# TLS: self-signed and Let's Encrypt share server.crt/server.key.
# scripts/sync-mosquitto-le-cert.sh replaces them in place after Caddy
# issues mqtt.freedriver.io.
set -euo pipefail

SECRETS=/opt/freedriver-secrets/mosquitto
STORAGE=/opt/freedriver-storage/mosquitto
IMAGE=eclipse-mosquitto:2.1.2-alpine
CN=mqtt.freedriver.io
GROUP=lonewatt-techops
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ACL_PLACEHOLDER='__INSTANCE_ID__'
# Lowercase UUID of any version: 8-4-4-4-12 lowercase hex, anchored at both ends.
INSTANCE_ID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

INSTANCE_ID="${INSTANCE_ID:-}"
ACL_TEMPLATE="${ACL_TEMPLATE:-${REPO_ROOT}/mosquitto/acl.template}"
ACL_OUT="${ACL_OUT:-}"
ACL_ONLY=0

usage() {
  cat <<'EOF'
Usage: provision-mosquitto.sh [--instance-id UUID] [--acl-only] [--acl-template PATH] [--acl-out PATH]

  INSTANCE_ID / --instance-id   House-minted UUID (kaze may hand it).
                                Substitutes __INSTANCE_ID__ in the template.
                                Do not invent a value. Version nibble is
                                not checked. No portal paste UI.
  --acl-only                    Substitute ACL only. No root, no secrets.
                                CI writes a temp file; never the live ACL.
  --acl-template PATH           Template (default: <repo>/mosquitto/acl.template)
  --acl-out PATH                Write substituted ACL here (required with
                                --acl-only). Full provision writes only
                                /opt/freedriver-secrets/mosquitto/acl.

Git is the template. Compose does not mount it as live. First-house apply
is Techops + the secrets file: exact instance topics in, leftover
freedriver/v1/home/... out, same write. live-commands stays false. Do not
open 1883.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --instance-id)
      INSTANCE_ID="${2:-}"
      shift 2
      ;;
    --acl-template)
      ACL_TEMPLATE="${2:-}"
      shift 2
      ;;
    --acl-out)
      ACL_OUT="${2:-}"
      shift 2
      ;;
    --acl-only)
      ACL_ONLY=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

is_instance_id() {
  [[ "$1" =~ $INSTANCE_ID_RE ]]
}

same_path() {
  local a="$1"
  local b="$2"
  [[ -e "$a" && -e "$b" ]] || return 1
  [[ "$(realpath "$a")" == "$(realpath "$b")" ]]
}

# Checks topic lines only; comment lines carry free text.
assert_exact_topic_acl() {
  local file="$1"
  local topics
  topics="$(grep -E '^topic ' "$file" || true)"
  if [[ -z "$topics" ]]; then
    echo "ACL has no topic lines: ${file}" >&2
    exit 1
  fi
  if grep -E '^topic .*(home/|[+#]|freedriver/v1/#)' <<<"$topics"; then
    echo "ACL topic lines must be exact instance topics (no leftover home/ segment, no + or #)." >&2
    exit 1
  fi
}

# One write replaces the destination: exact instance topics in, leftover
# freedriver/v1/home/... out. Exits non-zero on an instanceId that fails
# INSTANCE_ID_RE, on the git template as destination, and on topic lines with
# home/, wildcards, or __INSTANCE_ID__.
apply_instance_acl() {
  local instance_id="$1"
  local template="$2"
  local out="$3"

  if ! is_instance_id "$instance_id"; then
    echo "INSTANCE_ID must be a UUID (hex+hyphens). Do not enforce a version nibble. Do not invent a house id." >&2
    exit 1
  fi
  case "$instance_id" in
    *[+\#/]*)
      echo "INSTANCE_ID must not contain / + #." >&2
      exit 1
      ;;
  esac
  if [[ ! -f "$template" ]]; then
    echo "ACL template missing: ${template}" >&2
    exit 1
  fi
  if ! grep -Fq "$ACL_PLACEHOLDER" "$template"; then
    echo "ACL template must contain ${ACL_PLACEHOLDER} for apply-time substitution." >&2
    exit 1
  fi
  assert_exact_topic_acl "$template"
  if same_path "$out" "$template"; then
    echo "Refusing to overwrite the git template as if it were the live ACL." >&2
    exit 1
  fi

  local tmp
  tmp="$(mktemp)"
  # Placeholder is a fixed token and instance_id is lowercase hex+hyphens,
  # so both are literal in sed. Substitution edits topic lines; comments keep
  # the documented token.
  sed "/^topic /s/${ACL_PLACEHOLDER}/${instance_id}/g" "$template" > "$tmp"
  if grep -E '^topic ' "$tmp" | grep -Fq "$ACL_PLACEHOLDER"; then
    echo "ACL topic lines still contain ${ACL_PLACEHOLDER} after substitution." >&2
    rm -f "$tmp"
    exit 1
  fi
  if grep -E '^topic ' "$tmp" | grep -Fv "/${instance_id}/"; then
    echo "ACL topic lines must include the applied instanceId." >&2
    rm -f "$tmp"
    exit 1
  fi
  assert_exact_topic_acl "$tmp"

  local dest_dir
  dest_dir="$(dirname "$out")"
  mkdir -p "$dest_dir"
  umask 022
  cat "$tmp" > "$out"
  rm -f "$tmp"
  if grep -E '^topic .*(home/|[+#]|__INSTANCE_ID__)' "$out"; then
    echo "Applied ACL still has leftover home/, wildcards, or the placeholder." >&2
    exit 1
  fi
  echo "Wrote exact-topic ACL to ${out} (leftover freedriver/v1/home/... dropped in this write)."
}

if [[ "$ACL_ONLY" -eq 1 ]]; then
  if [[ -z "$INSTANCE_ID" ]]; then
    echo "--acl-only requires INSTANCE_ID or --instance-id (house-minted uuid; kaze may hand it)." >&2
    exit 1
  fi
  if [[ -z "$ACL_OUT" ]]; then
    echo "--acl-only requires --acl-out or ACL_OUT." >&2
    exit 1
  fi
  apply_instance_acl "$INSTANCE_ID" "$ACL_TEMPLATE" "$ACL_OUT"
  exit 0
fi

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root (sudo). Use --acl-only to substitute an ACL without root." >&2
  exit 1
fi

if ! getent group "$GROUP" >/dev/null; then
  echo "Group ${GROUP} is missing; create it before re-running." >&2
  exit 1
fi

if ! command -v docker >/dev/null; then
  echo "docker is required to build the passwd file with mosquitto_passwd." >&2
  exit 1
fi

mkdir -p "$SECRETS" "$STORAGE"

write_pass() {
  local path="$1"
  if [[ -e "$path" ]]; then
    echo "Leaving existing $(basename "$path") in place."
    return
  fi
  umask 037
  openssl rand -base64 24 > "$path"
  chown "root:${GROUP}" "$path"
  chmod 640 "$path"
  echo "Wrote $(basename "$path") (contents not printed)."
}

write_pass "${SECRETS}/autonomy.pass"
write_pass "${SECRETS}/api.pass"

if [[ ! -e "${SECRETS}/passwd" ]]; then
  # Official image runs as uid 1883; write the passwd file as root.
  # The container reads each password from its pass file on the secrets mount.
  docker run --rm --user root \
    -v "${SECRETS}:/mosquitto/config/secrets" \
    "$IMAGE" \
    sh -c 'mosquitto_passwd -c -b /mosquitto/config/secrets/passwd autonomy "$(tr -d "\n" < /mosquitto/config/secrets/autonomy.pass)" && mosquitto_passwd -b /mosquitto/config/secrets/passwd api "$(tr -d "\n" < /mosquitto/config/secrets/api.pass)"'
  echo "Built Mosquitto passwd file."
else
  echo "passwd already exists; not rebuilding it."
fi

if [[ -e "${SECRETS}/server.crt" && -e "${SECRETS}/server.key" ]]; then
  echo "TLS certs already present; leaving them in place (will not overwrite Let's Encrypt)."
elif [[ -e "${SECRETS}/server.crt" || -e "${SECRETS}/server.key" ]]; then
  echo "Partial TLS material exists; refusing to overwrite. Fix by hand." >&2
  exit 1
else
  # Prefer Caddy's already-issued LE pair over writing a new self-signed.
  if CADDY_CERTS=/opt/freedriver-storage/caddy/data/caddy/certificates \
     DEST_DIR="$SECRETS" \
     "${SCRIPT_DIR}/sync-mosquitto-le-cert.sh" --once; then
    echo "Installed Let's Encrypt ${CN} from Caddy (no self-signed written)."
  else
    openssl req -x509 -newkey rsa:4096 -sha256 -days 365 -nodes \
      -keyout "${SECRETS}/server.key" \
      -out "${SECRETS}/server.crt" \
      -subj "/CN=${CN}" \
      -addext "subjectAltName=DNS:${CN}"
    echo "Wrote self-signed cert for ${CN} (365 days). Compose sidecar replaces it in place once Caddy issues LE; no leftover self-signed filename."
  fi
fi

if [[ -n "$INSTANCE_ID" ]]; then
  apply_instance_acl "$INSTANCE_ID" "$ACL_TEMPLATE" "${SECRETS}/acl"
  if [[ -n "$ACL_OUT" ]]; then
    apply_instance_acl "$INSTANCE_ID" "$ACL_TEMPLATE" "$ACL_OUT"
  fi
  echo "Live ACL is ${SECRETS}/acl (compose secrets mount). Git template is not mounted."
  echo "Restart the mosquitto container to load the applied ACL. This script does not restart it, Quarkus does not SSH or restart it, and this does not enable live-commands."
else
  echo "INSTANCE_ID unset: left the git template unchanged and did not write ${SECRETS}/acl."
  echo "First-house apply is Techops + that secrets file. The house mints instanceId; kaze may hand it. Do not invent a UUID."
fi

chown "root:${GROUP}" "$SECRETS"
chmod 750 "$SECRETS"
find "$SECRETS" -type f -exec chown "root:${GROUP}" {} +
find "$SECRETS" -type f -exec chmod 640 {} +

# Broker uid 1883 enters the secrets dir (root:1883 750) and owns
# passwd/TLS/ACL. server.key is 0600. Plaintext *.pass stay
# root:lonewatt-techops 640.
chown "root:1883" "$SECRETS"
chmod 750 "$SECRETS"
for f in passwd server.crt server.key acl; do
  if [[ -e "${SECRETS}/${f}" ]]; then
    chown 1883:1883 "${SECRETS}/${f}"
  fi
done
[[ -e "${SECRETS}/server.key" ]] && chmod 0600 "${SECRETS}/server.key"
[[ -e "${SECRETS}/server.crt" ]] && chmod 0640 "${SECRETS}/server.crt"
[[ -e "${SECRETS}/passwd" ]] && chmod 0640 "${SECRETS}/passwd"
[[ -e "${SECRETS}/acl" ]] && chmod 0640 "${SECRETS}/acl"
for f in autonomy.pass api.pass; do
  if [[ -e "${SECRETS}/${f}" ]]; then
    chown "root:${GROUP}" "${SECRETS}/${f}"
    chmod 640 "${SECRETS}/${f}"
  fi
done

chown 1883:1883 "$STORAGE"
chmod 750 "$STORAGE"

echo "Mosquitto secrets in ${SECRETS}; persistence in ${STORAGE}."
echo "Sysadmin still needs ${CN} A → 138.197.90.42. Compose binds 8883 only (no 1883)."
