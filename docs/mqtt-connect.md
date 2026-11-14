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

The live ACL is `/opt/freedriver-secrets/mosquitto/acl`. `mosquitto.conf` reads it as `acl_file /mosquitto/config/secrets/acl` through the existing `/opt/freedriver-secrets/mosquitto` bind. Git keeps the template, `mosquitto/acl.template`, with `__INSTANCE_ID__` on instance topic lines.

Copy-paste apply (Techops, root/sudo), with `__INSTANCE_ID__` replaced by the first-house instanceId:

```
INSTANCE_ID=__INSTANCE_ID__ ./scripts/provision-mosquitto.sh
```

(`./scripts/provision-mosquitto.sh --instance-id __INSTANCE_ID__` is the same.)

The script accepts a lowercase UUID only. It checks the value before writing the ACL and exits non-zero when the value is missing, empty, the literal `__INSTANCE_ID__`, or malformed.

That **one write** puts instance topics into `/opt/freedriver-secrets/mosquitto/acl` and drops leftover `freedriver/v1/home/...` in the same step:

- autonomy write `freedriver/v1/__INSTANCE_ID__/appliances`
- autonomy read `freedriver/v1/__INSTANCE_ID__/commands`
- autonomy write `freedriver/v1/__INSTANCE_ID__/metrics/#`
- api read `freedriver/v1/+/appliances`
- api write `freedriver/v1/+/commands`
- api read `freedriver/v1/+/metrics/#`

The live ACL sits outside the deploy checkout (`/opt/freedriver-web`), so deploys leave it as applied.

**Techops runs the script, then restarts mosquitto** to load the ACL. Deploy creates and chowns `/opt/freedriver-storage/mosquitto` (uid 1883). CI runs `--acl-only` into a temp file.

The script is idempotent: existing pass and cert files stay in place. The broker starts once the secrets ACL exists.

## Autonomy connect

Autonomy (home) host, TLS, user, topics, retain, QoS, and JSON: [autonomy-mqtt.md](autonomy-mqtt.md). Portal REST: [appliances.md](appliances.md).

## Broker

- Image: `eclipse-mosquitto:2.1.2-alpine` with `iotinga/mosquitto-oauth-plugin` compiled against Mosquitto 2.1.2 (`mosquitto/Dockerfile`). Compose builds that image. Deploy pushes `ghcr.io/kazetsukaimiko/freedriver-web/mosquitto`.
- Plugin path `/usr/lib/mosquitto-oauth-plugin.so`. Client id `freedriver-mqtt`. Token URL `https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/token`. `plugin_opt_oauth_jwt_validate_exp false` so a short access token leaves a connected house up. Client secret and realm public key live in `/opt/freedriver-secrets/mosquitto/oauth.d/plugin.conf`, written by `scripts/provision-keycloak-freedriver.sh`. The image entrypoint copies that file onto the runtime conf after the plugin line.
- Keycloak MQTT users receive topic grants from the OAuth plugin JWT (`mqtt_acl`). `password_file` and `acl_file` remain the live-house fallback until [#321](https://github.com/kazetsukaimiko/freedriver-web/issues/321) installs that house on Keycloak.
- Two MQTTS listeners (`protocol mqtt`). Mosquitto has no SNI, so each socket has one certificate.
  - Container port 8883 is not published. The API dials hostname `mosquitto` on that port. The certificate SAN is `DNS:mosquitto` only, signed by `/opt/freedriver-secrets/mqtt-internal/ca.crt`.
  - Container port 8884 is published as host `8883`. That certificate is Let's Encrypt for `DNS:mqtt.freedriver.io` only.
- Host port 8883 stays reachable from any address because the home IP is dynamic. Broker passwords and instance ACLs guard it. `allow_anonymous false`, the password file, and the ACL apply to both listeners. Do not set `listener_allow_anonymous true`. Port 1883 stays closed.
- Sysadmin still needs `mqtt.freedriver.io` A → `138.197.90.42`.
- Auth: every client logs in with a broker password (`allow_anonymous false`). v1 has one house, which shares broker users `autonomy` and `api`. The OAuth plugin is loaded. The dashboard service account `service-account-freedriver-mqtt` (group `portal-broker`) publishes `freedriver/v1/+/commands` and subscribes to `freedriver/v1/+/appliances` through the plugin. House users receive exact instance topics from JWT claims. `api` reads `freedriver/v1/+/appliances`, writes `freedriver/v1/+/commands`, and reads `freedriver/v1/+/metrics/#`.
- Persistence: `/opt/freedriver-storage/mosquitto`.
- Command-live arrives with #27.

## Secrets (on the VPS)

| Path | What |
| --- | --- |
| `/opt/freedriver-secrets/mosquitto/autonomy.pass` | autonomy password |
| `/opt/freedriver-secrets/mosquitto/api.pass` | api password |
| `/opt/freedriver-secrets/mosquitto/passwd` | hashed broker passwd file |
| `/opt/freedriver-secrets/mosquitto/acl` | **live** instance ACL (Techops apply) |
| `/opt/freedriver-secrets/mosquitto/server.crt` | Public listener certificate (Let's Encrypt `DNS:mqtt.freedriver.io`) |
| `/opt/freedriver-secrets/mosquitto/server.key` | Public listener key (mode 0600, owner 1883) |
| `/opt/freedriver-secrets/mosquitto/internal.crt` | Unpublished listener leaf (`DNS:mosquitto` only) |
| `/opt/freedriver-secrets/mosquitto/internal.key` | Unpublished listener key (mode 0600, owner 1883) |
| `/opt/freedriver-secrets/mosquitto/oauth.d/plugin.conf` | Broker plugin client secret and realm public key |
| `/opt/freedriver-secrets/keycloak-freedriver-mqtt.env` | `freedriver-mqtt` client secret and service-account password |
| `/opt/freedriver-secrets/mqtt-internal/ca.crt` | Internal CA certificate that signed `internal.crt`. The CA private key is not kept. The dashboard mounts this file read-only. |

## Topics and ACL

Instance users keep exact appliances, commands, and metrics topics. `api` uses `+` so a newly provisioned house is heard without an ACL edit. Instance id is the same source as appliances and commands (host broker ACL).

| Topic | Writer | Reader |
| --- | --- | --- |
| `freedriver/v1/{instanceId}/appliances` | that instance's autonomy user | api via `freedriver/v1/+/appliances` |
| `freedriver/v1/{instanceId}/commands` | api via `freedriver/v1/+/commands` | that instance's autonomy user |
| `freedriver/v1/{instanceId}/metrics/#` | that instance's autonomy user | — |
| `freedriver/v1/+/metrics/#` | — | api |

Publishers set retain=false on appliances and commands; retain is a publisher setting. Grafana/Alloy ingest from the existing dashboard path.

## Let's Encrypt on host 8883 (Techops)

Let's Encrypt is the public listener: host `mqtt.freedriver.io:8883` maps to container port 8884. Caddy issues the name via the 404 stub (HTTP-01). The `mosquitto-cert-sync` sidecar copies that cert onto `/opt/freedriver-secrets/mosquitto/server.{crt,key}` only and SIGHUPs mosquitto through the shared PID namespace. It does not write `internal.crt` or `internal.key`. Manual: `scripts/sync-mosquitto-le-cert.sh`. Re-running provision keeps the live public pair in place.

The unpublished listener is a different certificate. `scripts/issue-mqtt-internal-cert.sh` (also run from `scripts/provision-mosquitto.sh`) writes `/opt/freedriver-secrets/mqtt-internal/ca.crt` and the `internal.crt` / `internal.key` pair. The CA private key is deleted in the same run. The dashboard mounts only `ca.crt`, and only once that file exists. A missing bind-mount source becomes a directory. The API mount does not include `internal.key`.

Run the issuer on the VPS before the broker restarts onto the two-listener config. If `internal.crt` is missing, Mosquitto will not start, and host 8883 stops with it.

## Quarkus

On the compose network, Quarkus connects to hostname `mosquitto` port 8883. That socket presents `DNS:mosquitto`, not the public Let's Encrypt leaf. `mqtt.freedriver.io:8883` is the name for public and home clients. Prod mounts `/opt/freedriver-secrets/mqtt-internal/ca.crt` into the API. Instance ids come from `FREEDRIVER_MQTT_INSTANCE_IDS` when that is set, otherwise from `freedriver/v1/<uuid>/appliances` lines in `/opt/freedriver-secrets/mosquitto/acl`. Password is `FREEDRIVER_MQTT_API_PASSWORD` when that is set, otherwise `/opt/freedriver-secrets/mosquitto/api.pass`.

Compose injects OIDC on the `dashboard` service only (not the image, not the SPA):

- `QUARKUS_OIDC_AUTH_SERVER_URL=https://auth.freedriver.io/realms/freedriver`
- `QUARKUS_OIDC_CLIENT_ID=freedriver-api`
- `QUARKUS_OIDC_CREDENTIALS_SECRET` from `/opt/freedriver-secrets/.env` (copied from `keycloak-freedriver-api.secret`)

The BFF (web-app, HTTP-only cookie, `freedriver-api` confidential client) is wired, and compose already injects the env.

## Keycloak (auth for the portal)

- Issuer: `https://auth.freedriver.io/realms/freedriver`
- Realm: `freedriver` (display name Freedriver)
- Confidential client: `freedriver-api` — **Quarkus BFF only.**
- Redirect URIs: `https://app.freedriver.io/*`, `http://localhost:8080/*` (Yuni local BFF)
- Post-logout redirect URI: `https://app.freedriver.io/` exactly, not a wildcard. `scripts/provision-keycloak-freedriver.sh` writes that as `post.logout.redirect.uris`. Techops applies it on production.
- Web origins: `https://app.freedriver.io`, `http://localhost:8080`
- Client secret file: `/opt/freedriver-secrets/keycloak-freedriver-api.secret` (mode 640, `root:lonewatt-techops`)
- Realm roles: `dashboard`, `portal-admin`
- Users: `kazetsukai` (dashboard + portal-admin), placeholder `second` (dashboard only)

Provision on the VPS with `scripts/provision-keycloak-freedriver.sh`. It is idempotent: it keeps an existing client secret file, and user passwords are managed in Keycloak.
