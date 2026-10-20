#!/bin/bash
# Compose liveness check for GET /health/live. The sms runtime image has no Python and no curl.
exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
printf 'GET /health/live HTTP/1.0\r\n\r\n' >&3 || exit 1
IFS= read -r status <&3 || exit 1
case "$status" in
  *" 200 "*) exit 0 ;;
  *) exit 1 ;;
esac
