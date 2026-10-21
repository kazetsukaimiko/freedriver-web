#!/bin/sh
# The provider jar may contain only sms-model, sms-api, and sms-client,
# plus the Keycloak SPI classes. Jackson and jakarta.validation-api stay out.
# Java release 21 is class-file major 65. The manifest names the source commit.
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: check-sms-otp-provider-jar.sh <jar> <commit>" >&2
  exit 2
fi

jar_path=$1
expected=$2

if [ ! -f "$jar_path" ]; then
  echo "missing provider jar: $jar_path" >&2
  exit 1
fi
jar_path=$(CDPATH= cd -- "$(dirname "$jar_path")" && pwd)/$(basename "$jar_path")

list=$(mktemp)
mf_dir=$(mktemp -d)
cleanup() {
  rm -f "$list"
  rm -rf "$mf_dir"
}
trap cleanup EXIT

jar tf "$jar_path" | sort > "$list"

need() {
  if ! grep -qx "$1" "$list"; then
    echo "provider jar missing $1" >&2
    exit 1
  fi
}

need io/freedriver/sms/model/OtpSendRequest.class
need io/freedriver/sms/api/SmsApi.class
need io/freedriver/sms/client/SmsClient.class
need io/freedriver/keycloak/sms/SmsOtpAuthenticator.class
need META-INF/services/org.keycloak.authentication.AuthenticatorFactory
need META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory

if grep -qx 'io/freedriver/keycloak/sms/SmsOtpClient.class' "$list"; then
  echo "hand-written SmsOtpClient is still in the provider jar" >&2
  exit 1
fi

if grep -E '^(com/fasterxml/jackson/|jakarta/validation/|jakarta/ws/rs/|org/junit/|org/hibernate/validator/)' "$list"; then
  echo "provider jar bundled a class outside sms-model, sms-api, and sms-client" >&2
  exit 1
fi

major() {
  class_name=$1
  if ! javap -verbose -classpath "$jar_path" "$class_name" | grep -q 'major version: 65'; then
    echo "$class_name is not Java release 21 (major 65)" >&2
    javap -verbose -classpath "$jar_path" "$class_name" | grep 'major version' >&2 || true
    exit 1
  fi
}

major io.freedriver.sms.client.SmsClient
major io.freedriver.sms.api.SmsApi
major io.freedriver.sms.model.OtpSendRequest
major io.freedriver.keycloak.sms.SmsOtpAuthenticator

for entry in $(grep '^io/freedriver/keycloak/sms/.*\.class$' "$list"); do
  class_name=$(printf '%s' "$entry" | sed 's#/#.#g; s#\.class$##')
  if javap -verbose -classpath "$jar_path" "$class_name" | grep -E -q 'jakarta/validation/(Validator|Validation)|org/hibernate/validator/'; then
    echo "$class_name references a Validator" >&2
    exit 1
  fi
done

(
  cd "$mf_dir"
  jar xf "$jar_path" META-INF/MANIFEST.MF
)
if ! tr -d '\r' < "$mf_dir/META-INF/MANIFEST.MF" | grep -q "^Implementation-SCM-Revision: ${expected}$"; then
  echo "manifest commit is not ${expected}" >&2
  cat "$mf_dir/META-INF/MANIFEST.MF" >&2
  exit 1
fi

echo "provider jar bundles sms-model, sms-api, sms-client only; commit ${expected}"
