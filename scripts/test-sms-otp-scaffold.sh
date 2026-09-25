#!/usr/bin/env bash
# Checks for the sms service and the Keycloak OTP scaffold. Runs with the git placeholder.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "$1" >&2
  exit 1
}

if grep -q 'sms.freedriver.io' Caddyfile; then
  fail "do not add a Caddy site for sms"
fi
if grep -q 'sms.freedriver.io' docker-compose.yml; then
  fail "compose must not publish an sms hostname"
fi

python3 - <<'PY'
import re
from pathlib import Path
compose = Path("docker-compose.yml").read_text()
marker = "\n  sms:\n"
start = compose.find(marker)
if start < 0:
    raise SystemExit("compose is missing the sms service")
rest = compose[start + 1 :]
nxt = re.search(r"\n  [A-Za-z0-9_-]+:\n", rest[1:])
block = rest if nxt is None else rest[: nxt.start() + 1]
if re.search(r"^\s+ports:\s*$", block, re.M):
    raise SystemExit("sms must not publish ports")
if "8080:8080" in block or '"8080:' in block or "'8080:" in block:
    raise SystemExit("sms must not map host port 8080")
REQUIRED = "SMS_OTP_SHARED_SECRET: ${SMS_OTP_SHARED_SECRET:?"
env = re.search(r"^    environment:\n((?:      .*\n)+)", block, re.M)
keys = [] if env is None else re.findall(r"^      ([A-Z0-9_]+):", env.group(1), re.M)
if "env_file" in block or keys != ["SMS_OTP_SHARED_SECRET"] or REQUIRED not in block:
    raise SystemExit("sms environment is exactly SMS_OTP_SHARED_SECRET, required via ${SMS_OTP_SHARED_SECRET:?}")
if "sms-otp.env.example" in block or "sms-otp.env.example" in compose:
    raise SystemExit("compose must not load the git secret example")
if "SMS_OTP_SHARED_SECRET" not in block:
    raise SystemExit("sms must receive SMS_OTP_SHARED_SECRET")
kc = compose.split("\n  keycloak:\n", 1)[1].split("\n  keycloak-db:\n", 1)[0]
if "env_file" in kc or REQUIRED not in kc:
    raise SystemExit("keycloak gets SMS_OTP_SHARED_SECRET via ${SMS_OTP_SHARED_SECRET:?} in environment")
if "AWS_" in kc or "AKIA" in kc:
    raise SystemExit("do not put AWS credentials on the keycloak service")
if "keycloak/Dockerfile" not in kc:
    raise SystemExit("keycloak image must bake the SMS OTP provider")
print("compose sms is internal")
PY

if grep -q 'sms-otp.env.example' docker-compose.yml; then
  fail "compose must not reference the example secret file"
fi
if [[ ! -f secrets/sms-otp.env.example ]]; then
  fail "missing secrets/sms-otp.env.example"
fi
if ! grep -q 'NOT A LIVE SECRET' secrets/sms-otp.env.example; then
  fail "example secret file must say it is not live"
fi
if ! grep -qx 'SMS_OTP_SHARED_SECRET=placeholder-not-a-live-secret' secrets/sms-otp.env.example; then
  fail "example must keep the rejected placeholder and nothing else as the value"
fi
# The example is the only tracked assignment of this variable.
if [[ "$(grep -c '^SMS_OTP_SHARED_SECRET=' secrets/sms-otp.env.example)" != "1" ]]; then
  fail "example secret file must contain one placeholder assignment"
fi
if grep -RInE '^SMS_OTP_SHARED_SECRET=.+' \
  --exclude 'sms-otp.env.example' \
  --exclude-dir .git --exclude-dir target \
  sms keycloak secrets scripts docs README.md docker-compose.yml Caddyfile; then
  fail "do not commit a live SMS_OTP_SHARED_SECRET assignment"
fi

if grep -RInE 'AKIA[0-9A-Z]{16}|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|aws_secret_access_key' \
  sms keycloak secrets scripts/provision-keycloak-sms-otp.sh scripts/check-sms-otp-flow.py \
  docker-compose.yml docs/sms-otp.md README.md; then
  fail "do not commit AWS credentials"
fi

grep -qx 'freedriver.appliances.live-commands=false' app/src/main/resources/application.properties
grep -qx 'quarkus.oidc.enabled=false' app/src/main/resources/application.properties

grep -qx 'io.freedriver.keycloak.sms.SmsOtpAuthenticatorFactory' \
  keycloak/sms-otp-spi/src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory

for needle in placeholder-not-a-live-secret X-Freedriver-Sms-Secret; do
  grep -q "$needle" sms/stub/server.py
  grep -q "$needle" keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/SmsOtpConfig.java
done
grep -q 'http://sms:8080' keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/SmsOtpConfig.java
grep -q 'placeholder-not-a-live-secret' scripts/provision-keycloak-sms-otp.sh

# The provision script keeps the password provider REQUIRED and SMS at ALTERNATIVE.
if grep -n 'auth-username-password-form' scripts/provision-keycloak-sms-otp.sh | grep -i 'DISABLED'; then
  fail "provision script must not disable the password authenticator"
fi
if grep -E 'requirement.*REQUIRED' scripts/provision-keycloak-sms-otp.sh | grep -i 'sms'; then
  fail "provision script must not set SMS OTP to REQUIRED"
fi

bash -n scripts/provision-keycloak-sms-otp.sh

python3 scripts/check-sms-otp-flow.py scripts/fixtures/sms-otp-flow-password-only.json >/dev/null
python3 scripts/check-sms-otp-flow.py scripts/fixtures/sms-otp-flow-ok.json >/dev/null
python3 scripts/check-sms-otp-flow.py --require-sms scripts/fixtures/sms-otp-flow-ok.json >/dev/null
if python3 scripts/check-sms-otp-flow.py --require-sms scripts/fixtures/sms-otp-flow-password-only.json >/dev/null 2>&1; then
  fail "checker must reject a flow that has no SMS execution when SMS is required"
fi
for bad in password-disabled sms-required password-missing forms-disabled; do
  if python3 scripts/check-sms-otp-flow.py "scripts/fixtures/sms-otp-flow-${bad}.json" >/dev/null 2>&1; then
    fail "checker must reject scripts/fixtures/sms-otp-flow-${bad}.json"
  fi
done

python3 sms/stub/test_stub.py

echo "sms otp scaffold locks ok"
