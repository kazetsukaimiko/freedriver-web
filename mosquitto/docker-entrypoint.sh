#!/bin/ash
set -e

# The secrets mount is read-only. include_dir fails when oauth.d is absent,
# so the runtime conf drops that line until provision writes the directory.
base=/mosquitto/config/mosquitto.conf
runtime=/tmp/mosquitto.conf
sed '/^include_dir[[:space:]]/d' "$base" > "$runtime"
if [ -d /mosquitto/config/secrets/oauth.d ]; then
	printf '\ninclude_dir /mosquitto/config/secrets/oauth.d\n' >> "$runtime"
fi

if [ "$#" -ge 3 ] && [ "$1" = "/usr/sbin/mosquitto" ] && [ "$2" = "-c" ]; then
	shift 3
	set -- /usr/sbin/mosquitto -c "$runtime" "$@"
fi

exec /docker-entrypoint.sh "$@"
