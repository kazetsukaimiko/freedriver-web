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
SECRET = "SMS_OTP_SHARED_SECRET: ${SMS_OTP_SHARED_SECRET:-}"
env = re.search(r"^    environment:\n((?:      .*\n)+)", block, re.M)
keys = [] if env is None else re.findall(r"^      ([A-Z0-9_]+):", env.group(1), re.M)
SEED = "FREEDRIVER_SEED_PHONE: ${FREEDRIVER_SEED_PHONE:-}"
CAP = "FREEDRIVER_SMS_DAILY_SEND_CAP: ${FREEDRIVER_SMS_DAILY_SEND_CAP:?FREEDRIVER_SMS_DAILY_SEND_CAP is required}"
if keys != ["SMS_OTP_SHARED_SECRET", "FREEDRIVER_SEED_PHONE", "FREEDRIVER_SMS_DAILY_SEND_CAP"] or SECRET not in block or SEED not in block or CAP not in block:
    raise SystemExit("sms environment is SMS_OTP_SHARED_SECRET, FREEDRIVER_SEED_PHONE, and a required FREEDRIVER_SMS_DAILY_SEND_CAP")
if compose.count("FREEDRIVER_SEED_PHONE") != 2:
    raise SystemExit("only sms receives FREEDRIVER_SEED_PHONE")
if compose.count("FREEDRIVER_SMS_DAILY_SEND_CAP") != 3 or compose.replace(block, "").count("FREEDRIVER_SMS_DAILY_SEND_CAP") != 0:
    raise SystemExit("only sms receives FREEDRIVER_SMS_DAILY_SEND_CAP")
if "sms/src/main/docker/Dockerfile.compose" not in block or "\n      context: .\n" not in block:
    raise SystemExit("sms builds from sms/src/main/docker/Dockerfile.compose with context .")
if "python3" in block:
    raise SystemExit("sms healthcheck must not use python3")
if "/usr/local/bin/health-live.sh" not in block or "/health/live" not in Path("sms/src/main/docker/health-live.sh").read_text():
    raise SystemExit("sms healthcheck must call /health/live")
if "/opt/freedriver-storage/sms:/deployments/data" not in block:
    raise SystemExit("sms mounts a persistent volume at /deployments/data")
TWILIO_ENV = (
    "    env_file:\n"
    "      - path: /opt/freedriver-secrets/twilio-verify.env\n"
    "        required: true\n"
)
if TWILIO_ENV not in block:
    raise SystemExit("sms loads /opt/freedriver-secrets/twilio-verify.env as a required env_file")
if compose.count("twilio-verify.env") != 1:
    raise SystemExit("twilio-verify.env is loaded once, by sms")
dash = compose.split("\n  dashboard:\n", 1)[1].split("\n  agent-dispatcher:\n", 1)[0]
invite_env = (
    "    env_file:\n"
    "      - path: /opt/freedriver-secrets/keycloak-freedriver-invite.env\n"
    "        required: false\n"
)
if invite_env not in dash:
    raise SystemExit("dashboard loads the invite secret env file, and it is not required before provision")
if "keycloak-freedriver-invite.env" in compose.replace(dash, ""):
    raise SystemExit("only dashboard loads the invite secret env file")
instances_env = (
    "      - path: /opt/freedriver-secrets/keycloak-freedriver-instances.env\n"
    "        required: false\n"
)
if instances_env not in dash:
    raise SystemExit("dashboard loads the instances admin secret env file, and it is not required before provision")
if "keycloak-freedriver-instances.env" in compose.replace(dash, ""):
    raise SystemExit("only dashboard loads the instances admin secret env file")
if compose.count("\n    env_file:\n") != 2:
    raise SystemExit("env_file is only the sms twilio file and the dashboard secret files")
if "TWILIO_" in compose.replace(block, ""):
    raise SystemExit("only sms receives TWILIO_ variables")
if "sms-otp.env.example" in block or "sms-otp.env.example" in compose:
    raise SystemExit("compose must not load the git secret example")
if "SMS_OTP_SHARED_SECRET" not in block:
    raise SystemExit("sms must receive SMS_OTP_SHARED_SECRET")
kc = compose.split("\n  keycloak:\n", 1)[1].split("\n  keycloak-db:\n", 1)[0]
if "env_file" in kc or SECRET not in kc:
    raise SystemExit("keycloak environment has SMS_OTP_SHARED_SECRET: ${SMS_OTP_SHARED_SECRET:-}")
if "AWS_" in kc or "AKIA" in kc:
    raise SystemExit("do not put AWS credentials on the keycloak service")
if "keycloak/Dockerfile" not in kc:
    raise SystemExit("keycloak image must bake the SMS OTP provider")
print("compose sms is internal")
PY

if grep -q 'sms-otp.env.example' docker-compose.yml; then
  fail "compose must not reference the example secret file"
fi
if grep -q 'twilio-verify.env.example' docker-compose.yml; then
  fail "compose must not reference the Twilio example file"
fi
if [[ "$(grep -v '^#' secrets/twilio-verify.env.example)" != "$(printf '%s=\n' TWILIO_ACCOUNT_SID TWILIO_API_KEY_SID TWILIO_API_KEY_SECRET TWILIO_VERIFY_SERVICE_SID)" ]]; then
  fail "Twilio example file holds the four names with empty values"
fi
if grep -RInE '^TWILIO_[A-Z_]+=.+' \
  --exclude-dir .git --exclude-dir target \
  sms keycloak secrets scripts docs README.md docker-compose.yml Caddyfile .env.example; then
  fail "do not commit a Twilio credential value"
fi
if [[ "$(grep '^FREEDRIVER_SEED_PHONE=' .env.example)" != "FREEDRIVER_SEED_PHONE=" ]]; then
  fail ".env.example holds FREEDRIVER_SEED_PHONE with an empty value"
fi
if [[ "$(grep '^FREEDRIVER_SMS_DAILY_SEND_CAP=' .env.example)" != "FREEDRIVER_SMS_DAILY_SEND_CAP=20" ]]; then
  fail ".env.example holds FREEDRIVER_SMS_DAILY_SEND_CAP as the placeholder 20"
fi
if ! grep -B2 '^FREEDRIVER_SMS_DAILY_SEND_CAP=20$' .env.example | grep -q 'placeholder'; then
  fail ".env.example comments the daily send cap placeholder"
fi
if grep -RInE '^FREEDRIVER_SMS_DAILY_SEND_CAP=.+' \
  --exclude-dir .git --exclude-dir target \
  --exclude '.env.example' \
  sms keycloak secrets scripts docs README.md docker-compose.yml Caddyfile; then
  fail "do not commit a live daily send cap"
fi
if grep -RInE '^\s*(export\s+)?FREEDRIVER_SEED_PHONE=.+' \
  --exclude-dir .git --exclude-dir target \
  sms keycloak secrets scripts docs README.md docker-compose.yml Caddyfile .env.example; then
  fail "do not commit a FREEDRIVER_SEED_PHONE value"
fi
bash -n scripts/provision-keycloak-freedriver.sh
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

grep -qx 'freedriver.appliances.live-commands=false' java/dashboard/src/main/resources/application.properties
grep -qx 'quarkus.oidc.enabled=false' java/dashboard/src/main/resources/auth-policy.properties

grep -qx 'io.freedriver.keycloak.sms.SmsOtpAuthenticatorFactory' \
  keycloak/sms-otp-spi/src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory

for needle in placeholder-not-a-live-secret X-Freedriver-Sms-Secret; do
  grep -q "$needle" keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/SmsOtpConfig.java
done
grep -q 'http://sms:8080' keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/SmsOtpConfig.java
grep -q 'placeholder-not-a-live-secret' scripts/provision-keycloak-sms-otp.sh

# The provision script keeps the password provider REQUIRED and SMS off REQUIRED.
if grep -n 'auth-username-password-form' scripts/provision-keycloak-sms-otp.sh | grep -i 'DISABLED'; then
  fail "provision script must not disable the password authenticator"
fi
if grep -E 'requirement.*REQUIRED' scripts/provision-keycloak-sms-otp.sh | grep -i 'sms'; then
  fail "provision script must not set SMS OTP to REQUIRED"
fi
if grep -q 'registrationAllowed=true' scripts/provision-keycloak-sms-otp.sh; then
  fail "provision script must not enable self-registration"
fi
grep -q 'registrationAllowed=false' scripts/provision-keycloak-sms-otp.sh
grep -q 'freedriver-phone-username-router' scripts/provision-keycloak-sms-otp.sh
grep -q 'freedriver-sms-route-condition' scripts/provision-keycloak-sms-otp.sh
grep -q -- '--count-usernames' scripts/provision-keycloak-sms-otp.sh
grep -q -- '--apply-username-rule' scripts/provision-keycloak-sms-otp.sh
grep -q -- '--plan' scripts/provision-keycloak-sms-otp.sh

# docker exec receives the admin password by variable name.
grep -q 'docker exec -e TECHOPS_PASS "\$CONTAINER"' scripts/provision-keycloak-sms-otp.sh
if grep -q -- '-e TECHOPS_PASS=' scripts/provision-keycloak-sms-otp.sh; then
  fail "pass TECHOPS_PASS to docker exec by name"
fi

bash -n scripts/provision-keycloak-sms-otp.sh

# Empty or placeholder secret still inserts the flow. browserFlow is not inside that check.
python3 - <<'PY'
from pathlib import Path
script = Path("scripts/provision-keycloak-sms-otp.sh").read_text()
if script.count("browserFlow=") != 1:
    raise SystemExit("bind browserFlow once")
bind = [line for line in script.splitlines() if "browserFlow=" in line]
if len(bind) != 1 or bind[0][:1].isspace():
    raise SystemExit("browserFlow bind must not sit inside the secret check")
if "placeholder-not-a-live-secret" not in script:
    raise SystemExit("script must recognize the placeholder secret")
if "No secret was written." not in script:
    raise SystemExit("placeholder run must say it wrote no secret")
print("browser flow binds with or without a real secret")
PY

# Phone sign-in pages come from the freedriver login theme through context.form().
THEME=keycloak/themes/freedriver/login
grep -qx 'parent=keycloak.v2' "$THEME/theme.properties"
for template in freedriver-sms-phone.ftl freedriver-sms-code.ftl; do
  grep -q "\"$template\"" keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/SmsOtpAuthenticator.java
  grep -q 'url.loginRestartFlowUrl' "$THEME/$template"
done
grep -q 'keycloak/themes/freedriver /opt/keycloak/themes/freedriver' keycloak/Dockerfile
if grep -q 'SMS_OTP_SHARED_SECRET' keycloak/Dockerfile; then
  fail "Dockerfile must not mention SMS_OTP_SHARED_SECRET"
fi
if grep -E 'GITHUB_TOKEN|GH_TOKEN|PACKAGES_TOKEN|settings.xml' keycloak/Dockerfile; then
  fail "Dockerfile must not take a package-registry token"
fi
grep -q 'eclipse-temurin-23' keycloak/Dockerfile
grep -q './mvnw -C -pl sms-client -am package' keycloak/Dockerfile
grep -q 'secrets' .dockerignore
grep -q '\*\*/\*\.env' .dockerignore
if grep -RIn 'SmsOtpClient' keycloak/sms-otp-spi/src; then
  fail "hand-written SmsOtpClient must be removed"
fi
if grep -RIn 'java.net.http' keycloak/sms-otp-spi/src/main; then
  fail "provider must call sms-client, not its own HTTP client"
fi
if grep -RInE 'jakarta\.validation\.(Validator|Validation)' keycloak/sms-otp-spi/src/main; then
  fail "provider must not run a Validator"
fi
for module in sms-model sms-api sms-client; do
  if ! grep -q '<maven.compiler.release>21</maven.compiler.release>' "$module/pom.xml"; then
    fail "$module must stay on Java release 21"
  fi
done
# Authenticators render theme pages. The invite realm resource is a JSON API, not a page.
if grep -RIn 'Response.ok' keycloak/sms-otp-spi/src/main --include='*Authenticator*.java'; then
  fail "render authenticator pages with context.form()"
fi

python3 scripts/check-sms-otp-flow.py --self-test >/dev/null
python3 scripts/check-sms-otp-flow.py --browser-source scripts/fixtures/sms-otp-flow-password-only.json >/dev/null
python3 scripts/check-sms-otp-flow.py scripts/fixtures/sms-otp-flow-ok.json >/dev/null
python3 scripts/check-sms-otp-flow.py --require-sms scripts/fixtures/sms-otp-flow-ok.json >/dev/null
if python3 scripts/check-sms-otp-flow.py scripts/fixtures/sms-otp-flow-password-only.json >/dev/null 2>&1; then
  fail "checker must reject a flow that is not the signed phone-username shape"
fi
cookie_err="$(mktemp)"
router_err="$(mktemp)"
if python3 scripts/check-sms-otp-flow.py scripts/fixtures/sms-otp-flow-cookie-missing.json >/dev/null 2>"$cookie_err"; then
  fail "checker must reject a flow with no auth-cookie"
fi
grep -q 'auth-cookie is missing' "$cookie_err" || fail "checker must say auth-cookie is missing"
if python3 scripts/check-sms-otp-flow.py scripts/fixtures/sms-otp-flow-router-ahead.json >/dev/null 2>"$router_err"; then
  fail "checker must reject a router ahead of auth-cookie"
fi
grep -q 'router is ahead of auth-cookie' "$router_err" || fail "checker must say the router is ahead of auth-cookie"
rm -f "$cookie_err" "$router_err"
for bad in password-disabled sms-required password-missing forms-disabled sms-first; do
  if python3 scripts/check-sms-otp-flow.py "scripts/fixtures/sms-otp-flow-${bad}.json" >/dev/null 2>&1; then
    fail "checker must reject scripts/fixtures/sms-otp-flow-${bad}.json"
  fi
done

if grep -q 'app/pom.xml' sms/src/main/docker/Dockerfile.compose; then
  fail "sms image build must not copy a missing app module"
fi
if ! grep -q 'java/pom.xml' sms/src/main/docker/Dockerfile.compose; then
  fail "sms image build must see the reactor poms"
fi
if ! grep -q '185:0 /opt/freedriver-storage/sms' .github/workflows/deploy.yml; then
  fail "deploy must check the sms data directory owner"
fi
if ! grep -q 'Storage mode for /opt/freedriver-storage/sms' .github/workflows/deploy.yml; then
  fail "deploy must check the sms data directory mode"
fi
python3 - <<'PY'
from pathlib import Path
text = Path(".github/workflows/deploy.yml").read_text()
overlay = Path("docker-compose.ghcr.yml").read_text()
marks = [
    'build "$service"',
    "Push images to GHCR",
    "Pull images on the VPS",
    "rsync -az",
    "--no-build",
]
positions = []
for mark in marks:
    at = text.find(mark)
    if at < 0:
        raise SystemExit(f"deploy.yml missing {mark}")
    positions.append(at)
if positions != sorted(positions):
    raise SystemExit("deploy must build, push, and pull before rsync")
pull = text.split("Pull images on the VPS", 1)[1].split("Rsync and start containers", 1)[0]
if "docker pull" in pull or "docker login" in pull:
    raise SystemExit("deploy must pull with compose, not the account docker client")
if "pull --policy always" not in pull:
    raise SystemExit("deploy must pull with compose --policy always")
if "deploy-stage" not in pull:
    raise SystemExit("deploy must stage the pull outside the live tree")
if "/opt/freedriver-web/" in pull:
    raise SystemExit("the pull step must not rsync the live tree")
for line in text.splitlines():
    if "--build" in line and "--no-build" not in line:
        raise SystemExit("deploy must not build images on the VPS")
for name in ("keycloak", "sms"):
    if f"freedriver-web/{name}:" not in overlay:
        raise SystemExit(f"deploy must build and push {name}")
print("deploy builds keycloak and sms on the runner before rsync")
PY

python3 - <<'PY'
import re
from pathlib import Path

def java_string(source, marker):
    chunk = source.split(marker, 1)[1].split(";", 1)[0]
    parts = re.findall(r'"((?:\\.|[^"\\])*)"', chunk)
    return "".join(bytes(p, "utf-8").decode("unicode_escape") for p in parts)

spi = Path("keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/SignInAgreement.java").read_text()
sms = Path("sms-model/src/main/java/io/freedriver/sms/validation/SignInAgreementWording.java").read_text()
spi_text = java_string(spi, "WORDING =")
sms_text = java_string(sms, "String TEXT =")
if not spi_text or spi_text != sms_text:
    raise SystemExit("sign-in agreement wording drifted between the SPI and sms")

phone = Path("keycloak/themes/freedriver/login/freedriver-sms-phone.ftl").read_text()
if "By tapping Send code" in phone:
    raise SystemExit("agreement wording must not be copied into the theme")
if "${freedriverSmsAgreement}" not in phone or 'id="freedriver-sms-agreement"' not in phone:
    raise SystemExit("phone page shows the shared agreement wording")
if not (phone.index('name="phone"') < phone.index('id="freedriver-sms-agreement"') < phone.index('id="freedriver-sms-send"')):
    raise SystemExit("agreement wording sits directly above the Send code button")

router = Path("keycloak/themes/freedriver/login/freedriver-phone-username.ftl").read_text()
if "By tapping Send code" in router:
    raise SystemExit("agreement wording must not be copied into the theme")
if "${freedriverSmsAgreement}" not in router or 'id="freedriver-sms-agreement"' not in router:
    raise SystemExit("router page shows the shared agreement wording")
if "freedriverSmsSend" in router or 'name="send"' in router:
    raise SystemExit("router page has a separate Send code button")
if not (router.index('name="username"') < router.index('id="freedriver-sms-agreement"') < router.index("freedriverPhoneUsernameContinue")):
    raise SystemExit("agreement wording sits above Continue")
fragment = Path("keycloak/themes/freedriver/login/resources/js/phone-fragment.js").read_text()
if "phone-fragment.js" not in router:
    raise SystemExit("router page does not load the fragment script")
if "location.hash" not in fragment or "sessionStorage" not in fragment or "freedriver.login.phone" not in fragment:
    raise SystemExit("fragment script does not keep the number")
if "location.search" in fragment or "URLSearchParams" in fragment:
    raise SystemExit("fragment script reads the query")

code = Path("keycloak/themes/freedriver/login/freedriver-sms-code.ftl").read_text()
if "disabled" in code.lower():
    raise SystemExit("verify stays enabled")
if "freedriverSmsCode" not in code or "loginButton" not in code:
    raise SystemExit("the code page can keep the typed code and still submits Verify")

messages = Path("keycloak/themes/freedriver/login/messages/messages_en.properties").read_text().splitlines()
expected = [
    "freedriverSmsSend=Send code",
    "freedriverSmsWrongCode=That code didn''t work. Try again or send a new code.",
    "freedriverSmsCodeExpiry=Your code works for 5 minutes.",
    "freedriverSmsExpired=That code expired. Send a new code.",
    "freedriverSmsTooManyCodes=Too many wrong codes. Send a new code.",
    "freedriverSmsRateLimited=Too many tries. Try again in {0} minutes.",
    "freedriverSmsRateLimitedOne=Too many tries. Try again in 1 minute.",
    "freedriverSmsCheckUnavailable=We couldn''t check your code right now. Try again in a minute.",
]
missing = [line for line in expected if line not in messages]
if missing:
    raise SystemExit("sign-in page text missing: " + "; ".join(missing))
print("sign-in agreement and page text share one wording")
PY

python3 - <<'PY'
import json, re, subprocess, tempfile, os
from pathlib import Path

def run(composite, mappings, expect):
    with tempfile.TemporaryDirectory() as tmp:
        cpath = Path(tmp) / "c.json"
        mpath = Path(tmp) / "m.json"
        cpath.write_text(json.dumps(composite))
        mpath.write_text(json.dumps(mappings))
        result = subprocess.run(
            ["python3", "scripts/check-phone-user-roles.py", str(cpath), str(mpath), "freedriver"],
            capture_output=True, text=True)
    if (result.returncode == 0) != expect:
        raise SystemExit(result.stderr or result.stdout or "phone role check mismatch")

allowed = [
    {"name": "dashboard"},
    {"name": "default-roles-freedriver"},
    {"name": "offline_access"},
    {"name": "uma_authorization"},
]
run(allowed, {}, True)
run([{"name": "dashboard"}, {"name": "default-roles-freedriver"}], {}, True)
run([{"name": "portal-admin"}], {}, False)
run([{"name": "changelog"}], {}, False)
run(allowed + [{"name": "extra"}], {}, False)
run(allowed, {"clientMappings": {"account": {"mappings": [{"name": "view-profile"}]}}}, False)
script = Path("scripts/provision-keycloak-freedriver.sh").read_text()
phone = script.split("# Phone sign-in user kaze", 1)[1]
marker = phone.find("phone roles allow-list before the phone attribute")
write = phone.find("Set ${PHONE_ATTRIBUTE} on user")
if marker < 0 or write < 0 or marker > write:
    raise SystemExit("group and role checks must run before the phone attribute is written")
if r"^\+1[2-9][0-9]{9}$" not in script:
    raise SystemExit("seed phone check must be +1 and ten digits starting 2-9")
if "adminPermissionsEnabled=true" not in script or "freedriver-invite" not in script:
    raise SystemExit("provision script must enable admin permissions and the invite client")
body = script.split("ensure_invite_service_account() {", 1)[1]
depth = 1
kept = []
for line in body.splitlines():
    if line.strip() == "{":
        depth += 1
    elif line.strip() == "}":
        depth -= 1
        if depth == 0:
            break
    kept.append(line)
invite_body = "\n".join(kept)
if "add-roles" in invite_body:
    raise SystemExit("invite setup must not grant roles with add-roles")
if "map-roles" in invite_body or "impersonate" in invite_body:
    raise SystemExit("invite permissions must not include map-roles or impersonate")
accounts = Path("keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/InviteAccounts.java").read_text()
resource = Path("keycloak/sms-otp-spi/src/main/java/io/freedriver/keycloak/sms/InviteAccountResource.java").read_text()
if "user.grantRole(dashboard)" not in accounts or accounts.count("grantRole(") != 1:
    raise SystemExit("dashboard role is set only in InviteAccounts")
if "credential" in accounts.lower():
    raise SystemExit("invite account create must not set a password")
if "portal-admin" not in accounts or "REALM_MANAGEMENT_CLIENT_ID" not in accounts:
    raise SystemExit("InviteAccounts must still refuse portal-admin and realm-management")
if "map-roles" in resource or "role-mappings" in resource or "add-roles" in resource:
    raise SystemExit("invite-account resource must not gain a general role grant")
if 'update "${path}/${perm_id}"' not in script:
    raise SystemExit("existing invite permission must be updated")
if re.search(r'"(view|view-members|manage-members)"', invite_body):
    raise SystemExit("invite scopes must be manage-membership and manage-group-membership")
print("phone role allow-list and invite permission scope")
PY

if grep -RIn 'offline_access' java/dashboard keycloak/themes >/dev/null; then
  fail "the app must not request offline_access"
fi
if ! grep -q 'Referrer-Policy "no-referrer"' Caddyfile; then
  fail "Caddy must set Referrer-Policy no-referrer"
fi
python3 - <<'PY'
from pathlib import Path
text = Path("Caddyfile").read_text()
start = text.find("app.freedriver.io {")
end = text.find("\n}\n", start)
block = text[start:end]
invite = block.split("@invite path /invite /invite/*", 1)
if len(invite) != 2 or 'header Referrer-Policy "no-referrer"' not in invite[1].split("handle {", 1)[0]:
    raise SystemExit("Referrer-Policy no-referrer applies to /invite/ only")
print("invite responses set Referrer-Policy")
PY
if [[ "$(grep -v '^#' secrets/keycloak-freedriver-invite.env.example | grep -v '^$')" != "FREEDRIVER_INVITE_CLIENT_SECRET=" ]]; then
  fail "invite example file holds one empty assignment"
fi
if ! grep -q 'Placeholder for the dashboard invite client' secrets/keycloak-freedriver-invite.env.example; then
  fail "invite example file must mark the assignment as a placeholder"
fi
if [[ "$(grep -v '^#' secrets/keycloak-freedriver-instances.env.example | grep -v '^$')" != "FREEDRIVER_INSTANCES_CLIENT_SECRET=" ]]; then
  fail "instances example file holds one empty assignment"
fi
if ! grep -q 'Placeholder for the dashboard instances admin client' secrets/keycloak-freedriver-instances.env.example; then
  fail "instances example file must mark the assignment as a placeholder"
fi

python3 - <<'PY'
from pathlib import Path

script = Path("scripts/provision-keycloak-freedriver.sh").read_text()
for line in (
    "ACCESS_CODE_LIFESPAN_LOGIN=7200",
    "SSO_SESSION_IDLE_TIMEOUT=28800",
    "SSO_SESSION_MAX_LIFESPAN=86400",
    "ACCESS_CODE_LIFESPAN_USER_ACTION=1800",
):
    if line not in script:
        raise SystemExit(f"missing {line}")
if "ensure_session_timeouts() {" not in script:
    raise SystemExit("provision script must define ensure_session_timeouts")
body = script.split("ensure_session_timeouts() {", 1)[1]
depth = 1
kept = []
for line in body.splitlines():
    if line.strip() == "{":
        depth += 1
    elif line.strip() == "}":
        depth -= 1
        if depth == 0:
            break
    kept.append(line)
timeouts = "\n".join(kept)
if 'kcadm update "realms/${REALM}"' not in timeouts:
    raise SystemExit("session timeouts must update realms/${REALM}")
for flag in (
    'accessCodeLifespanLogin=${ACCESS_CODE_LIFESPAN_LOGIN}',
    'ssoSessionIdleTimeout=${SSO_SESSION_IDLE_TIMEOUT}',
    'ssoSessionMaxLifespan=${SSO_SESSION_MAX_LIFESPAN}',
    'accessCodeLifespanUserAction=${ACCESS_CODE_LIFESPAN_USER_ACTION}',
):
    if flag not in timeouts:
        raise SystemExit(f"session timeouts must set {flag}")
print("realm session timeouts 7200/28800/86400/1800")
PY

chmod +x scripts/test-invite-service-account.sh
./scripts/test-invite-service-account.sh

echo "sms otp scaffold locks ok"
