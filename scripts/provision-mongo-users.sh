#!/usr/bin/env bash
# One-time cutover: per-service Mongo databases and users (#505).
#
# Dashboard, sms, and agent-dispatcher each get their own database and a
# readWrite user that reaches only that database. Compose loads the connection
# string from /opt/freedriver-secrets/mongo-{dashboard,sms,dispatcher}.env.
#
# Run as root on the VPS after the host compose file starts mongod with --auth.
# Order: stop the three apps, mongodump freedriver, move the named collections,
# compare counts, write the env files, drop freedriver when those collections
# have moved, start the three apps.
#
# --check validates the repo files and exits. It does not talk to Mongo or the
# host secrets directory.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/freedriver-web}"
SECRETS_DIR="${SECRETS_DIR:-/opt/freedriver-secrets}"
SECRETS_ENV="${SECRETS_ENV:-${SECRETS_DIR}/.env}"
BUILD_ENV="${BUILD_ENV:-${COMPOSE_DIR}/.build-number.env}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/freedriver}"
MONGO_CONTAINER="${MONGO_CONTAINER:-freedriver-web-mongodb-1}"
GROUP="${GROUP:-lonewatt-techops}"
SOURCE_DB=freedriver

DASHBOARD_DB=freedriver_dashboard
SMS_DB=freedriver_sms
DISPATCHER_DB=freedriver_dispatcher

DASHBOARD_COLLECTIONS=(invites scheduled-switches appliance-roster)
SMS_COLLECTIONS=(phones sign-ins consents daily-send-cap)
DISPATCHER_COLLECTIONS=(agent-jobs agent-devices agent-locks)

CHECK_ONLY=0
if [[ "${1:-}" == "--check" ]]; then
  CHECK_ONLY=1
fi

fail() {
  echo "$1" >&2
  exit 1
}

unprofiled_database() {
  local file="$1"
  local expected="$2"
  python3 - "$file" "$expected" <<'PY'
import sys
path, expected = sys.argv[1], sys.argv[2]
wanted = "quarkus.mongodb.database=" + expected
for raw in open(path, encoding="utf-8"):
    line = raw.strip()
    if not line or line.startswith("#") or line.startswith("%"):
        continue
    if line.startswith("quarkus.mongodb.database="):
        if line != wanted:
            sys.exit(f"{path} database is {line}, want {wanted}")
        sys.exit(0)
sys.exit(f"{path} is missing unprofiled quarkus.mongodb.database")
PY
}

check_repo() {
  local compose props_dash props_sms props_disp script
  compose="$(cat "${ROOT}/docker-compose.yml")"
  props_dash="${ROOT}/java/dashboard/src/main/resources/application.properties"
  props_sms="${ROOT}/sms/src/main/resources/application.properties"
  props_disp="${ROOT}/java/agent-dispatcher/src/main/resources/application.properties"
  script="$(cat "${ROOT}/scripts/provision-mongo-users.sh")"

  [[ "$compose" == *'path: /opt/freedriver-secrets/mongo-dashboard.env'* ]] \
    || fail "compose loads mongo-dashboard.env"
  [[ "$compose" == *'path: /opt/freedriver-secrets/mongo-sms.env'* ]] \
    || fail "compose loads mongo-sms.env"
  [[ "$compose" == *'path: /opt/freedriver-secrets/mongo-dispatcher.env'* ]] \
    || fail "compose loads mongo-dispatcher.env"
  [[ "$compose" == *'command: ["mongod", "--bind_ip_all", "--auth"]'* ]] \
    || fail "mongodb starts with --auth"
  [[ "$compose" == *'FREEDRIVER_APPLIANCES_LIVE_COMMANDS: "true"'* ]] \
    || fail "compose keeps FREEDRIVER_APPLIANCES_LIVE_COMMANDS true"
  [[ "$compose" != *'QUARKUS_MONGODB_CONNECTION_STRING: ${'* ]] \
    || fail "compose interpolates QUARKUS_MONGODB_CONNECTION_STRING"
  [[ "$compose" != *'QUARKUS_MONGODB_DATABASE:'* ]] \
    || fail "compose still sets QUARKUS_MONGODB_DATABASE"
  [[ "$compose" != *'mongodb://mongodb:27017/freedriver'* ]] \
    || fail "compose still defaults a shared freedriver URI"

  unprofiled_database "$props_dash" "$DASHBOARD_DB"
  unprofiled_database "$props_sms" "$SMS_DB"
  unprofiled_database "$props_disp" "$DISPATCHER_DB"

  local coll
  for coll in "${DASHBOARD_COLLECTIONS[@]}" "${SMS_COLLECTIONS[@]}" "${DISPATCHER_COLLECTIONS[@]}"; do
    [[ "$script" == *"$coll"* ]] || fail "script names collection ${coll}"
  done
  echo "provision-mongo-users --check ok"
}

if [[ "$CHECK_ONLY" == 1 ]]; then
  check_repo
  exit 0
fi

check_repo

[[ "$(id -u)" == 0 ]] || fail "run as root on the VPS"
command -v docker >/dev/null || fail "docker is required"
command -v openssl >/dev/null || fail "openssl is required"
[[ -d "$COMPOSE_DIR" ]] || fail "missing ${COMPOSE_DIR}"
[[ -f "${COMPOSE_DIR}/docker-compose.yml" ]] || fail "missing ${COMPOSE_DIR}/docker-compose.yml"
mkdir -p "$SECRETS_DIR"
chmod 0750 "$SECRETS_DIR"

compose() {
  local -a files=(
    --env-file "$SECRETS_ENV"
    -f "${COMPOSE_DIR}/docker-compose.yml"
  )
  if [[ -f "$BUILD_ENV" ]]; then
    files+=(--env-file "$BUILD_ENV")
  fi
  if [[ -f "${COMPOSE_DIR}/docker-compose.ghcr.yml" ]]; then
    files+=(-f "${COMPOSE_DIR}/docker-compose.ghcr.yml")
  fi
  (cd "$COMPOSE_DIR" && docker compose "${files[@]}" "$@")
}

mongo_eval() {
  docker exec -i "$MONGO_CONTAINER" mongosh --quiet --eval "$1"
}

mongo_js() {
  docker exec -i "$MONGO_CONTAINER" mongosh --quiet
}

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="${BACKUP_ROOT}/mongo-505-${stamp}"
mkdir -p "$backup_dir"
chmod 0700 "$backup_dir"

echo "stopping dashboard, sms, and agent-dispatcher"
compose stop dashboard sms agent-dispatcher

echo "dumping ${SOURCE_DB} to ${backup_dir}"
docker exec "$MONGO_CONTAINER" mongodump --archive --db="$SOURCE_DB" \
  > "${backup_dir}/${SOURCE_DB}.archive"
chmod 0600 "${backup_dir}/${SOURCE_DB}.archive"
echo "dump written"

count_js() {
  local db="$1"
  shift
  local colls=("$@")
  local coll json
  json='{'
  local first=1
  for coll in "${colls[@]}"; do
    [[ "$first" == 1 ]] || json+=', '
    first=0
    json+="\"${coll}\": db.getSiblingDB(\"${db}\").getCollection(\"${coll}\").countDocuments()"
  done
  json+='}'
  mongo_eval "JSON.stringify(${json})"
}

before_dashboard="$(count_js "$SOURCE_DB" "${DASHBOARD_COLLECTIONS[@]}")"
before_sms="$(count_js "$SOURCE_DB" "${SMS_COLLECTIONS[@]}")"
before_dispatcher="$(count_js "$SOURCE_DB" "${DISPATCHER_COLLECTIONS[@]}")"
printf '%s\n' "$before_dashboard" > "${backup_dir}/counts-dashboard-before.json"
printf '%s\n' "$before_sms" > "${backup_dir}/counts-sms-before.json"
printf '%s\n' "$before_dispatcher" > "${backup_dir}/counts-dispatcher-before.json"

move_one() {
  local dest_db="$1"
  local coll="$2"
  mongo_js <<JS
const srcDb = "${SOURCE_DB}";
const destDb = "${dest_db}";
const coll = "${coll}";
const src = db.getSiblingDB(srcDb);
const dest = db.getSiblingDB(destDb);
const srcNames = src.getCollectionNames();
const destNames = dest.getCollectionNames();
const srcHas = srcNames.includes(coll);
const destHas = destNames.includes(coll);
if (srcHas && destHas) {
  throw new Error(srcDb + "." + coll + " and " + destDb + "." + coll + " both exist");
}
if (srcHas) {
  const result = db.adminCommand({
    renameCollection: srcDb + "." + coll,
    to: destDb + "." + coll,
    dropTarget: false
  });
  if (result.ok !== 1) {
    throw new Error("renameCollection failed for " + coll + ": " + JSON.stringify(result));
  }
  print("moved " + srcDb + "." + coll + " -> " + destDb + "." + coll);
} else if (destHas) {
  print("already in " + destDb + "." + coll);
} else {
  print("absent " + coll + " (empty)");
}
JS
}

echo "moving collections"
for coll in "${DASHBOARD_COLLECTIONS[@]}"; do
  move_one "$DASHBOARD_DB" "$coll"
done
for coll in "${SMS_COLLECTIONS[@]}"; do
  move_one "$SMS_DB" "$coll"
done
for coll in "${DISPATCHER_COLLECTIONS[@]}"; do
  move_one "$DISPATCHER_DB" "$coll"
done

after_dashboard="$(count_js "$DASHBOARD_DB" "${DASHBOARD_COLLECTIONS[@]}")"
after_sms="$(count_js "$SMS_DB" "${SMS_COLLECTIONS[@]}")"
after_dispatcher="$(count_js "$DISPATCHER_DB" "${DISPATCHER_COLLECTIONS[@]}")"
printf '%s\n' "$after_dashboard" > "${backup_dir}/counts-dashboard-after.json"
printf '%s\n' "$after_sms" > "${backup_dir}/counts-sms-after.json"
printf '%s\n' "$after_dispatcher" > "${backup_dir}/counts-dispatcher-after.json"

python3 - "$before_dashboard" "$after_dashboard" "$before_sms" "$after_sms" \
  "$before_dispatcher" "$after_dispatcher" <<'PY'
import json, sys
pairs = [
    ("dashboard", sys.argv[1], sys.argv[2]),
    ("sms", sys.argv[3], sys.argv[4]),
    ("dispatcher", sys.argv[5], sys.argv[6]),
]
for name, before_raw, after_raw in pairs:
    before = json.loads(before_raw)
    after = json.loads(after_raw)
    if before != after:
        raise SystemExit(f"{name} counts differ: {before} -> {after}")
    print(f"{name} counts match {after}")
PY

remaining="$(mongo_eval "JSON.stringify(db.getSiblingDB('${SOURCE_DB}').getCollectionNames().filter(n => !n.startsWith('system.')))")"
python3 - "$remaining" <<'PY'
import json, sys
left = json.loads(sys.argv[1])
if left:
    raise SystemExit("freedriver still has collections " + ",".join(left) + "; dump is kept, database left in place")
print("freedriver has no remaining collections")
PY

echo "dropping ${SOURCE_DB}"
mongo_eval "db.getSiblingDB('${SOURCE_DB}').dropDatabase()"

write_env() {
  local database="$1"
  local password="$2"
  local env_file="$3"
  local tmp
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  printf 'QUARKUS_MONGODB_CONNECTION_STRING=mongodb://%s:%s@mongodb:27017/%s?authSource=%s\n' \
    "$database" "$password" "$database" "$database" > "$tmp"
  install -m 0640 -o root -g "$GROUP" "$tmp" "$env_file"
  rm -f "$tmp"
  echo "wrote $(basename "$env_file") mode 0640"
}

dash_env="${SECRETS_DIR}/mongo-dashboard.env"
sms_env="${SECRETS_DIR}/mongo-sms.env"
disp_env="${SECRETS_DIR}/mongo-dispatcher.env"
have_dash=0
have_sms=0
have_disp=0
[[ -f "$dash_env" ]] && have_dash=1
[[ -f "$sms_env" ]] && have_sms=1
[[ -f "$disp_env" ]] && have_disp=1
have_sum=$((have_dash + have_sms + have_disp))

if [[ "$have_sum" -eq 3 ]]; then
  echo "keeping existing mongo-*.env files"
elif [[ "$have_sum" -ne 0 ]]; then
  fail "partial mongo-*.env files; restore or remove all three, then rerun"
else
  echo "creating users and env files"
  dash_pw="$(openssl rand -hex 32)"
  sms_pw="$(openssl rand -hex 32)"
  disp_pw="$(openssl rand -hex 32)"
  docker exec \
    -e DASH_DB="$DASHBOARD_DB" -e DASH_PW="$dash_pw" \
    -e SMS_DB="$SMS_DB" -e SMS_PW="$sms_pw" \
    -e DISP_DB="$DISPATCHER_DB" -e DISP_PW="$disp_pw" \
    "$MONGO_CONTAINER" mongosh --quiet --eval '
      function ensure(database, password) {
        const d = db.getSiblingDB(database);
        if (d.getUser(database)) {
          throw new Error("user already exists for " + database);
        }
        d.createUser({
          user: database,
          pwd: password,
          roles: [{ role: "readWrite", db: database }]
        });
      }
      ensure(process.env.DASH_DB, process.env.DASH_PW);
      ensure(process.env.SMS_DB, process.env.SMS_PW);
      ensure(process.env.DISP_DB, process.env.DISP_PW);
    '
  write_env "$DASHBOARD_DB" "$dash_pw" "$dash_env"
  write_env "$SMS_DB" "$sms_pw" "$sms_env"
  write_env "$DISPATCHER_DB" "$disp_pw" "$disp_env"
  dash_pw=""
  sms_pw=""
  disp_pw=""
fi

echo "starting dashboard, sms, and agent-dispatcher"
compose up -d --no-build --no-deps dashboard sms agent-dispatcher
echo "provision-mongo-users done; dump at ${backup_dir}"
echo "dashboard ${after_dashboard}"
echo "sms ${after_sms}"
echo "dispatcher ${after_dispatcher}"
