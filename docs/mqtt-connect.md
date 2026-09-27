# MQTT connect — first-house Mosquitto (v1)

Agent-followable first-house provisioning. Broker/Keycloak wiring is #24; product MQTT is #21; command-live is #27. File shape for instance ACLs is on main (PR #87). Live VPS apply is [#59](https://github.com/kazetsukaimiko/freedriver-web/issues/59); #59 stays open until that apply runs on the VPS.

On GitHub: kaze / [@kazetsukaimiko](https://github.com/kazetsukaimiko).

## Locks (read before any VPS or client work)

- **Mint is locked.** The live ACL carries the first-house instanceId. Docs write it as `__INSTANCE_ID__`, the template placeholder. The apply command below is the repeatable procedure.
- House and appliance display names are UX-only and live in the portal/DB. Code, compose, ACLs, and topics identify a house by `instanceId`.

## Who mints `instanceId`

Long-term, **freedriver-web owns minting `instanceId`**. For v1, Techops applies the first-house instanceId through the secrets-file apply below.

`instanceId` is a lowercase UUID (8-4-4-4-12 hex, any version). Hex and hyphens keep it a single literal topic segment.

## First-house apply (Techops only)

The live ACL is `/opt/freedriver-secrets/mosquitto/acl`. `mosquitto.conf` reads it as `acl_file /mosquitto/config/secrets/acl` through the existing `/opt/freedriver-secrets/mosquitto` bind. Git keeps the template, `mosquitto/acl.template`, with `__INSTANCE_ID__` in its topic lines.

Copy-paste apply (Techops, root/sudo), with `__INSTANCE_ID__` replaced by the first-house instanceId:

```
INSTANCE_ID=__INSTANCE_ID__ ./scripts/provision-mosquitto.sh
```

(`./scripts/provision-mosquitto.sh --instance-id __INSTANCE_ID__` is the same.)

The script accepts a lowercase UUID only. It checks the value before writing the ACL and exits non-zero when the value is missing, empty, the literal `__INSTANCE_ID__`, or malformed.

That **one write** puts exact topics into `/opt/freedriver-secrets/mosquitto/acl` and drops leftover `freedriver/v1/home/...` in the same step:

- `freedriver/v1/__INSTANCE_ID__/appliances`
- `freedriver/v1/__INSTANCE_ID__/commands`

The live ACL sits outside the deploy checkout (`/opt/freedriver-web`), so deploys leave it as applied.

**Techops runs the script, then restarts mosquitto** to load the ACL. Deploy creates and chowns `/opt/freedriver-storage/mosquitto` (uid 1883). CI runs `--acl-only` into a temp file.

The script is idempotent: existing pass and cert files stay in place. The broker starts once the secrets ACL exists.

## Autonomy connect

Autonomy (home) host, TLS, user, topics, retain, QoS, and JSON: [autonomy-mqtt.md](autonomy-mqtt.md). Portal REST: [appliances.md](appliances.md).

## Broker

- Image: `eclipse-mosquitto:2.1.2-alpine`
- Listener: MQTTS on 8883 (`protocol mqtt`). Compose publishes host port 8883, reachable from any address because the home IP is dynamic; broker passwords and exact-topic ACLs guard it.
- Sysadmin still needs `mqtt.freedriver.io` A → `138.197.90.42`.
- Auth: every client logs in with a broker password (`allow_anonymous false`). v1 has one house, which shares broker users `autonomy` and `api`. Each later instance gets its own autonomy user.
- Persistence: `/opt/freedriver-storage/mosquitto`.
- Command-live arrives with #27.

## Secrets (on the VPS)

| Path | What |
| --- | --- |
| `/opt/freedriver-secrets/mosquitto/autonomy.pass` | autonomy password |
| `/opt/freedriver-secrets/mosquitto/api.pass` | api password |
| `/opt/freedriver-secrets/mosquitto/passwd` | hashed broker passwd file |
| `/opt/freedriver-secrets/mosquitto/acl` | **live** exact-topic ACL (Techops apply) |
| `/opt/freedriver-secrets/mosquitto/server.crt` | TLS cert |
| `/opt/freedriver-secrets/mosquitto/server.key` | TLS key |

## Topics and ACL

The ACL grants exact topics only, one pair per instance:

| Topic | Writer | Reader |
| --- | --- | --- |
| `freedriver/v1/{instanceId}/appliances` | that instance's autonomy user | api |
| `freedriver/v1/{instanceId}/commands` | api | that instance's autonomy user |

Publishers set retain=false on both topics; retain is a publisher setting.

## Let's Encrypt on 8883 (Techops)

Let's Encrypt is live on `mqtt.freedriver.io:8883`. Caddy issues the name via the 404 stub (HTTP-01). The `mosquitto-cert-sync` sidecar copies that cert onto `/opt/freedriver-secrets/mosquitto/server.{crt,key}` and SIGHUPs mosquitto through the shared PID namespace. Manual: `scripts/sync-mosquitto-le-cert.sh`. Re-running provision keeps the live pair in place.

## Quarkus

On the compose network, Quarkus connects to hostname `mosquitto` port 8883; `mqtt.freedriver.io` is the name for public/home clients. Instance ids come from `FREEDRIVER_MQTT_INSTANCE_IDS`. Password is `FREEDRIVER_MQTT_API_PASSWORD`.

Compose injects OIDC on the `app` service only:

- `QUARKUS_OIDC_AUTH_SERVER_URL=https://auth.freedriver.io/realms/freedriver`
- `QUARKUS_OIDC_CLIENT_ID=freedriver-api`
- `QUARKUS_OIDC_CREDENTIALS_SECRET` from `/opt/freedriver-secrets/.env` (copied from `keycloak-freedriver-api.secret`)

The BFF (web-app, HTTP-only cookie, `freedriver-api` confidential client) is wired, and compose already injects the env.

## Keycloak (auth for the portal)

- Issuer: `https://auth.freedriver.io/realms/freedriver`
- Realm: `freedriver` (display name Freedriver)
- Confidential client: `freedriver-api` — **Quarkus BFF only.**
- Redirect URIs: `https://app.freedriver.io/*`, `http://localhost:8080/*` (Yuni local BFF)
- Web origins: `https://app.freedriver.io`, `http://localhost:8080`
- Client secret file: `/opt/freedriver-secrets/keycloak-freedriver-api.secret` (mode 640, `root:lonewatt-techops`)
- Realm roles: `dashboard`, `portal-admin`
- Users: `kazetsukai` (dashboard + portal-admin), placeholder `second` (dashboard only)

Provision on the VPS with `scripts/provision-keycloak-freedriver.sh`. It is idempotent: it keeps an existing client secret file, and user passwords are managed in Keycloak.
