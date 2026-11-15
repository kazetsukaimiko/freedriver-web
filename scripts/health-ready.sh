#!/bin/bash
# Compose readiness check for GET /q/health/ready. The Quarkus runtime image has no curl.
exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1
printf 'GET /q/health/ready HTTP/1.0\r\n\r\n' >&3 || exit 1
IFS= read -r status <&3 || exit 1
case "$status" in
  *" 200 "*) exit 0 ;;
  *) exit 1 ;;
esac
