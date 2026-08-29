# MQTT connect (Mosquitto MQTTS)

Not live until the #25 lockdown is merged (PR #29). Broker and Keycloak role wiring is #24; product MQTT work is #21.

## Broker

- Host: `mqtt.freedriver.io:8883` (MQTTS / TLS). Compose publishes host 8883. Self-signed until Let's Encrypt; autonomy must verify TLS (no skip-verify).
- Protocol: MQTT only — no WebSockets, no plaintext 1883 listener
- Auth: v1 one house shares broker users `autonomy` and `api` (not Keycloak passwords). No anonymous. Exact-topic only — no `+`/`#` bootstrap. `api` is not a wildcard superuser. Each later instance gets its own autonomy user — do not share `autonomy` across instances.
- Image: `eclipse-mosquitto:2.1.2-alpine`
- QoS 1 is the client convention. The broker does not enforce QoS.

Sysadmin still needs `mqtt.freedriver.io` A → `138.197.90.42`. Let's Encrypt can replace the self-signed cert later. Home IP is treated as dynamic; 8883 is world-reachable with broker passwords and exact-topic ACLs. Not command-live until #27.

## Secrets (not in git)

| Path | What |
| --- | --- |
| `/opt/freedriver-secrets/mosquitto/autonomy.pass` | autonomy password |
| `/opt/freedriver-secrets/mosquitto/api.pass` | api password |
| `/opt/freedriver-secrets/mosquitto/passwd` | hashed broker passwd file |
| `/opt/freedriver-secrets/mosquitto/acl` | **live** exact-topic ACL (Techops apply; not in git) |
| `/opt/freedriver-secrets/mosquitto/server.crt` | TLS cert |
| `/opt/freedriver-secrets/mosquitto/server.key` | TLS key |

Git keeps `mosquitto/acl.template` only. Compose does **not** mount that template (or any git ACL) as live. `mosquitto.conf` sets `acl_file /mosquitto/config/secrets/acl` on the existing `/opt/freedriver-secrets/mosquitto` bind. Do not ship `__INSTANCE_ID__` or any fake id as the compose-mounted ACL. No portal paste UI.

Provision on the VPS with `scripts/provision-mosquitto.sh` (root/sudo). Idempotent; never overwrites existing pass or cert files. GitHub Actions must not run the secrets path — deploy only `mkdir`/`chown`s `/opt/freedriver-storage/mosquitto` (uid 1883). CI may run `--acl-only` into a temp file; it must not treat the repo template as the live ACL.

Persistence: `/opt/freedriver-storage/mosquitto`. The broker will not start until the secrets ACL exists — apply first. The provision script does not restart Mosquitto. Quarkus does not SSH or restart it.

## Topics

Exact topics only. No wildcards (`+`, `#`), no `$SYS`, no `freedriver/v1/#`. Never `freedriver/v1/+/appliances` or `freedriver/v1/+/commands`.

| Topic | Writer | Reader |
| --- | --- | --- |
| `freedriver/v1/{instanceId}/appliances` | that instance's autonomy user | api |
| `freedriver/v1/{instanceId}/commands` | api | that instance's autonomy user |

`instanceId` is a UUID (hex + hyphens) on the topic. Do not enforce a UUIDv4 version nibble. That already excludes `/`, `+`, `#`. `instanceName` is UX-only and is never a topic segment or ACL. Boards stay off MQTT.

v1 is one house: shared `autonomy` + `api` users, exact-topic only. `api` is not a wildcard superuser. A later instance gets its own autonomy broker user. Do not share one `autonomy` user across instances.

The first-house `instanceId` is minted by the house (UUID hex + hyphens). Do not invent a UUID in git. Do not add a portal paste UI. kaze may hand that house-minted id to Techops. First-house apply is **Techops + the secrets file**:

```
INSTANCE_ID=<house-minted-uuid> ./scripts/provision-mosquitto.sh
```

or `./scripts/provision-mosquitto.sh --instance-id <house-minted-uuid>`. That one write puts exact instance topics into `/opt/freedriver-secrets/mosquitto/acl` and drops leftover `freedriver/v1/home/...` in the same step. It does not write a checkout/git ACL. A later deploy cannot rsync the live ACL back to a placeholder.

**retain=false on both topics for v1.** The broker cannot forbid retain; publishers must set it. That instance's autonomy user must publish appliances with retain=false. `api` must publish commands with retain=false. Do not retain appliances. `live-commands` stays `false`. Do not open 1883.

## TLS

Self-signed cert for `mqtt.freedriver.io` (365 days) until Let's Encrypt replaces it. Home/autonomy clients should expect to trust that cert (or the later LE cert).

## Quarkus

On the compose network, connect to hostname `mosquitto` port 8883. Never use `mqtt.freedriver.io` from Quarkus — that name is for public/home clients.

Compose injects OIDC on the `app` service only (not the image, not the SPA):

- `QUARKUS_OIDC_AUTH_SERVER_URL=https://auth.freedriver.io/realms/freedriver`
- `QUARKUS_OIDC_CLIENT_ID=freedriver-api`
- `QUARKUS_OIDC_CREDENTIALS_SECRET` from `/opt/freedriver-secrets/.env` (copied from `keycloak-freedriver-api.secret`)

`quarkus.oidc.enabled` stays off until #25 and #27.


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
