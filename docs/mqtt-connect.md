# MQTT connect (Mosquitto MQTTS)

Not live until the #25 lockdown is merged (PR #29). Broker and Keycloak role wiring is #24; product MQTT work is #21.

## Broker

- Host: `mqtt.freedriver.io:8883` (MQTTS / TLS)
- Protocol: MQTT only — no WebSockets, no plaintext 1883 listener
- Auth: broker users `autonomy` and `api` (not Keycloak passwords). No anonymous.
- Image: `eclipse-mosquitto:2.1.2-alpine`
- QoS 1 is the client convention. The broker does not enforce QoS.

Sysadmin still needs `mqtt.freedriver.io` A → `138.197.90.42`. Let's Encrypt can replace the self-signed cert later. 8883 stays world-reachable unless Scott's home IP is stable enough to lock down.

## Secrets (not in git)

| Path | What |
| --- | --- |
| `/opt/freedriver-secrets/mosquitto/autonomy.pass` | autonomy password |
| `/opt/freedriver-secrets/mosquitto/api.pass` | api password |
| `/opt/freedriver-secrets/mosquitto/passwd` | hashed broker passwd file |
| `/opt/freedriver-secrets/mosquitto/server.crt` | TLS cert |
| `/opt/freedriver-secrets/mosquitto/server.key` | TLS key |

Provision on the VPS with `scripts/provision-mosquitto.sh` (root/sudo). Idempotent; never overwrites existing pass or cert files. GitHub Actions must not run that script — deploy only `mkdir`/`chown`s `/opt/freedriver-storage/mosquitto` (uid 1883).

Persistence: `/opt/freedriver-storage/mosquitto`.

## Topics

Exact topics only. No wildcards, no `$SYS`, no `#`.

| Topic | Writer | Reader |
| --- | --- | --- |
| `freedriver/v1/home/appliances` | autonomy | api |
| `freedriver/v1/home/commands` | api | autonomy |

**retain=false on both topics for v1.** The broker cannot forbid retain; publishers must set it. `autonomy` must publish appliances with retain=false. `api` must publish commands with retain=false. Do not retain appliances.

## TLS

Self-signed cert for `mqtt.freedriver.io` (365 days) until Let's Encrypt replaces it. Home/autonomy clients should expect to trust that cert (or the later LE cert).

## Quarkus

On the compose network, connect to hostname `mosquitto` port 8883. Never use `mqtt.freedriver.io` from Quarkus — that name is for public/home clients.

## Keycloak (auth, not MQTT)

- Issuer: `https://auth.freedriver.io/realms/freedriver`
- Realm: `freedriver` (display name Freedriver)
- Confidential client: `freedriver-api` — **Quarkus BFF only. The client secret must never go in the React SPA.**
- Redirect URIs: `https://app.freedriver.io/*`, `http://localhost:8080/*` (Yuni local BFF)
- Web origins: `https://app.freedriver.io`, `http://localhost:8080`
- Client secret file: `/opt/freedriver-secrets/keycloak-freedriver-api.secret` (mode 640, `root:lonewatt-techops`)
- Realm roles: `dashboard`, `portal-admin` (not `realm-admin` / master)
- Users: `kazetsukai` (dashboard + portal-admin), placeholder `second` (dashboard only)

Provision on the VPS with `scripts/provision-keycloak-freedriver.sh`. Idempotent. It does not rotate the client secret if the secret file already exists, and it does not set or print user passwords.

See #21 and #24.
