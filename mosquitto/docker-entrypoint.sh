#!/bin/ash
set -e

# The secrets mount is read-only. oauth.d/plugin.conf holds plugin_opt_* for
# the client secret and realm public key. Mosquitto 2.1 ties those options to
# the plugin line in the same file, so the runtime conf copies plugin.conf
# after that line. include_dir stays in the git conf as the secrets path.
base=/mosquitto/config/mosquitto.conf
runtime=/tmp/mosquitto.conf
sed '/^include_dir[[:space:]]/d' "$base" > "$runtime"
plugin_conf=/mosquitto/config/secrets/oauth.d/plugin.conf
if [ -f "$plugin_conf" ]; then
	printf '\n' >> "$runtime"
	cat "$plugin_conf" >> "$runtime"
fi

if [ "$#" -ge 3 ] && [ "$1" = "/usr/sbin/mosquitto" ] && [ "$2" = "-c" ]; then
	shift 3
	set -- /usr/sbin/mosquitto -c "$runtime" "$@"
fi

exec /docker-entrypoint.sh "$@"
