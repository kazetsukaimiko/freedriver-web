FROM alpine:3.21
RUN apk add --no-cache docker-cli openssl
COPY sync-mosquitto-le-cert.sh /usr/local/bin/sync-mosquitto-le-cert.sh
RUN chmod 0755 /usr/local/bin/sync-mosquitto-le-cert.sh
ENTRYPOINT ["/usr/local/bin/sync-mosquitto-le-cert.sh"]
CMD ["--watch"]
