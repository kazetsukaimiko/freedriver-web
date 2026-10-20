#!/usr/bin/env bash
# Issue the unpublished Mosquitto listener certificate (DNS:mosquitto only).
#
# Writes the CA certificate to MQTT_INTERNAL_DIR/ca.crt (default
# /opt/freedriver-secrets/mqtt-internal/ca.crt) and the leaf pair to
# MQTT_SECRETS_DIR/internal.crt and internal.key (default
# /opt/freedriver-secrets/mosquitto). The leaf is not server.crt or
# server.key; scripts/sync-mosquitto-le-cert.sh overwrites that public pair.
#
# The CA private key is created in a temp dir and removed before exit.
# It is not written under the CA directory, the mosquitto secrets directory,
# the repo, or an image. Re-issue replaces ca.crt because the key is not kept.
#
# Idempotent when the leaf still verifies against ca.crt, the SAN is only
# DNS:mosquitto, the key matches, and the leaf is not inside 30 days of expiry.
# Root chowns the leaf to uid/gid 1883 and the key to mode 0600.
set -euo pipefail

MQTT_INTERNAL_DIR="${MQTT_INTERNAL_DIR:-/opt/freedriver-secrets/mqtt-internal}"
MQTT_SECRETS_DIR="${MQTT_SECRETS_DIR:-/opt/freedriver-secrets/mosquitto}"
MQTT_UID="${MQTT_UID:-1883}"
MQTT_GID="${MQTT_GID:-1883}"
CA_DAYS="${CA_DAYS:-3650}"
LEAF_DAYS="${LEAF_DAYS:-825}"
RENEW_WITHIN_SECONDS="${RENEW_WITHIN_SECONDS:-2592000}"

CA_CRT="${MQTT_INTERNAL_DIR}/ca.crt"
LEAF_CRT="${MQTT_SECRETS_DIR}/internal.crt"
LEAF_KEY="${MQTT_SECRETS_DIR}/internal.key"

usage() {
  cat <<'EOF'
Usage: issue-mqtt-internal-cert.sh

  MQTT_INTERNAL_DIR   CA certificate directory (default: /opt/freedriver-secrets/mqtt-internal)
  MQTT_SECRETS_DIR    Leaf directory (default: /opt/freedriver-secrets/mosquitto)
  MQTT_UID / MQTT_GID Leaf owner when run as root (default: 1883)

Installs ca.crt, internal.crt, and internal.key. Does not install a CA private
key, does not write server.crt or server.key, and does not mount anything.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi
if [[ $# -gt 0 ]]; then
  echo "Unknown argument: $1" >&2
  usage >&2
  exit 1
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required." >&2
  exit 1
fi

internal_real="$(realpath -m "$MQTT_INTERNAL_DIR")"
secrets_real="$(realpath -m "$MQTT_SECRETS_DIR")"
if [[ "$internal_real" == "$secrets_real" ]]; then
  echo "The internal CA directory must not be the mosquitto secrets directory." >&2
  exit 1
fi

work=""
cleanup() {
  if [[ -n "$work" && -d "$work" ]]; then
    if [[ -f "$work/ca.key" ]] && command -v shred >/dev/null 2>&1; then
      shred -u "$work/ca.key" || rm -f "$work/ca.key"
    fi
    rm -rf "$work"
  fi
}
trap cleanup EXIT

san_is_only_mosquitto() {
  local cert="$1"
  local names
  names="$(openssl x509 -in "$cert" -noout -ext subjectAltName 2>/dev/null | grep -oE 'DNS:[^, ]+' | sort -u || true)"
  [[ "$names" == "DNS:mosquitto" ]]
}

key_matches_cert() {
  local cert="$1"
  local key="$2"
  local crt_pub key_pub
  crt_pub="$(openssl x509 -in "$cert" -noout -pubkey 2>/dev/null | openssl pkey -pubin -outform DER 2>/dev/null | openssl md5)"
  key_pub="$(openssl pkey -in "$key" -pubout -outform DER 2>/dev/null | openssl md5)"
  [[ -n "$crt_pub" && "$crt_pub" == "$key_pub" ]]
}

leaf_not_expiring() {
  openssl x509 -in "$1" -checkend "$RENEW_WITHIN_SECONDS" -noout >/dev/null 2>&1
}

ca_constraints_ok() {
  local text
  text="$(openssl x509 -in "$1" -noout -ext basicConstraints 2>/dev/null || true)"
  grep -q 'CA:TRUE' <<<"$text"
}

leaf_constraints_ok() {
  local text
  text="$(openssl x509 -in "$1" -noout -ext basicConstraints 2>/dev/null || true)"
  grep -q 'CA:FALSE' <<<"$text"
}

material_ok() {
  [[ -f "$CA_CRT" && -f "$LEAF_CRT" && -f "$LEAF_KEY" ]] || return 1
  openssl verify -CAfile "$CA_CRT" "$LEAF_CRT" >/dev/null 2>&1 || return 1
  san_is_only_mosquitto "$LEAF_CRT" || return 1
  key_matches_cert "$LEAF_CRT" "$LEAF_KEY" || return 1
  ca_constraints_ok "$CA_CRT" || return 1
  leaf_constraints_ok "$LEAF_CRT" || return 1
  leaf_not_expiring "$LEAF_CRT" || return 1
  # The public leaf must not chain to this CA.
  if [[ -f "${MQTT_SECRETS_DIR}/server.crt" ]]; then
    if openssl verify -CAfile "$CA_CRT" "${MQTT_SECRETS_DIR}/server.crt" >/dev/null 2>&1; then
      echo "Public server.crt verifies against the internal CA. Refusing to keep that CA." >&2
      return 1
    fi
  fi
  return 0
}

drop_stored_ca_key() {
  local path
  for path in "${MQTT_INTERNAL_DIR}/ca.key" "${MQTT_SECRETS_DIR}/ca.key" "${MQTT_INTERNAL_DIR}/ca.srl"; do
    if [[ -e "$path" ]]; then
      if command -v shred >/dev/null 2>&1 && [[ -f "$path" ]]; then
        shred -u "$path" || rm -f "$path"
      else
        rm -f "$path"
      fi
      echo "Removed $(basename "$path") from $(dirname "$path") (contents not printed)."
    fi
  done
}

apply_modes() {
  chmod 0644 "$CA_CRT"
  chmod 0640 "$LEAF_CRT"
  chmod 0600 "$LEAF_KEY"
  if [[ "$(id -u)" -eq 0 ]]; then
    chown "root:root" "$CA_CRT" "$MQTT_INTERNAL_DIR"
    chown "${MQTT_UID}:${MQTT_GID}" "$LEAF_CRT" "$LEAF_KEY"
  fi
}

assert_no_ca_key() {
  local found
  found="$(find "$MQTT_INTERNAL_DIR" "$MQTT_SECRETS_DIR" -maxdepth 1 -type f \( -name 'ca.key' -o -name 'ca.srl' \) -print 2>/dev/null || true)"
  if [[ -n "$found" ]]; then
    echo "CA private key material must not remain next to the certificates." >&2
    exit 1
  fi
  if [[ -e "${MQTT_INTERNAL_DIR}/internal.key" || -e "${MQTT_INTERNAL_DIR}/server.key" ]]; then
    echo "Leaf private keys must not be stored in ${MQTT_INTERNAL_DIR}." >&2
    exit 1
  fi
}

mkdir -p "$MQTT_INTERNAL_DIR" "$MQTT_SECRETS_DIR"
chmod 0755 "$MQTT_INTERNAL_DIR"

if material_ok; then
  drop_stored_ca_key
  apply_modes
  assert_no_ca_key
  echo "Leaving existing internal listener certificate in place (${CA_CRT})."
  exit 0
fi

work="$(mktemp -d)"
chmod 700 "$work"

cat > "${work}/ca.cnf" <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no
[dn]
CN = freedriver-mqtt-internal
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

openssl req -new -x509 -nodes -newkey rsa:2048 \
  -keyout "${work}/ca.key" \
  -out "${work}/ca.crt" \
  -sha256 -days "$CA_DAYS" \
  -config "${work}/ca.cnf" >/dev/null 2>&1
chmod 0600 "${work}/ca.key"

cat > "${work}/leaf.cnf" <<'EOF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = mosquitto
EOF

openssl req -new -nodes -newkey rsa:2048 \
  -keyout "${work}/internal.key" \
  -out "${work}/internal.csr" \
  -config "${work}/leaf.cnf" >/dev/null 2>&1
chmod 0600 "${work}/internal.key"

cat > "${work}/leaf.ext" <<'EOF'
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:mosquitto
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
EOF

openssl x509 -req \
  -in "${work}/internal.csr" \
  -CA "${work}/ca.crt" \
  -CAkey "${work}/ca.key" \
  -CAcreateserial \
  -out "${work}/internal.crt" \
  -sha256 -days "$LEAF_DAYS" \
  -extfile "${work}/leaf.ext" >/dev/null 2>&1

openssl verify -CAfile "${work}/ca.crt" "${work}/internal.crt" >/dev/null
san_is_only_mosquitto "${work}/internal.crt" || {
  echo "Internal leaf SAN must be DNS:mosquitto only." >&2
  exit 1
}
key_matches_cert "${work}/internal.crt" "${work}/internal.key" || {
  echo "Internal leaf key does not match the certificate." >&2
  exit 1
}
ca_constraints_ok "${work}/ca.crt" || {
  echo "Internal CA certificate is not a CA." >&2
  exit 1
}
leaf_constraints_ok "${work}/internal.crt" || {
  echo "Internal leaf must not be a CA." >&2
  exit 1
}

install_file() {
  local src="$1"
  local dest="$2"
  local mode="$3"
  local owner="${4:-}"
  local tmp
  tmp="$(mktemp "${dest}.tmp.XXXXXX")"
  cat "$src" > "$tmp"
  chmod "$mode" "$tmp"
  if [[ -n "$owner" && "$(id -u)" -eq 0 ]]; then
    chown "$owner" "$tmp"
  fi
  mv -f "$tmp" "$dest"
}

install_file "${work}/internal.key" "$LEAF_KEY" 0600 "${MQTT_UID}:${MQTT_GID}"
install_file "${work}/internal.crt" "$LEAF_CRT" 0640 "${MQTT_UID}:${MQTT_GID}"
install_file "${work}/ca.crt" "$CA_CRT" 0644 "root:root"

# Drop the CA key before any further checks. The temp dir goes away on exit.
if command -v shred >/dev/null 2>&1; then
  shred -u "${work}/ca.key" || rm -f "${work}/ca.key"
else
  rm -f "${work}/ca.key"
fi
rm -f "${work}/ca.srl" "${work}/internal.csr"

drop_stored_ca_key
apply_modes
assert_no_ca_key

if ! material_ok; then
  echo "Installed internal certificate failed verification." >&2
  exit 1
fi

if [[ -f "${MQTT_SECRETS_DIR}/server.crt" ]]; then
  if openssl verify -CAfile "$CA_CRT" "${MQTT_SECRETS_DIR}/server.crt" >/dev/null 2>&1; then
    echo "Public server.crt must not verify against ${CA_CRT}." >&2
    exit 1
  fi
fi

echo "Wrote internal CA certificate to ${CA_CRT} (CA private key not kept)."
echo "Wrote internal listener leaf to ${LEAF_CRT} (DNS:mosquitto only)."
