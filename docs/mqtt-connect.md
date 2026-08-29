# MQTT connect — first-house Mosquitto (v1)

Agent-followable first-house provisioning. Broker/Keycloak wiring is #24; product MQTT is #21; command-live is #27. File shape for instance ACLs is on main (PR #87). Live VPS apply is still [#59](https://github.com/kazetsukaimiko/freedriver-web/issues/59) — **do not close #59 from a docs PR.**

On GitHub: kaze / [@kazetsukaimiko](https://github.com/kazetsukaimiko).

## Locks (read before any VPS or client work)

- **Hold the VPS ACL apply until Security locks the mint.** This page is the guide. It is not permission to apply.
- Do **not** apply on the VPS from a docs change. Do **not** run `scripts/provision-mosquitto.sh` against live secrets from this page.
- Do **not** enable `live-commands`. Do **not** open 1883.
- Do **not** put a house display name or any appliance name in code, compose, ACL, or as a topic segment. Names are UX-only. The first house will have a display name in the portal/DB later — never bake that name into a config an agent would copy.
- Never put a broker password in this doc, in git, or in an issue.

## Who mints `instanceId`

Long-term, **freedriver-web owns minting `instanceId`**. First house is **not** an admin screen. Quarkus does **not** mint for v1 apply. Techops runs the secrets-file apply.

The first-house `instanceId` for the apply command (UUID hex+hyphens; do **not** enforce a UUIDv4 version nibble):

```
877b33d0-6e53-4212-a53f-52107383eec2
```

That already excludes `/`, `+`, `#`. Do not invent another UUID. Do not add a portal paste UI. The house does **not** mint the first id.

## First-house apply (Techops only, after Security lock)

Live ACL is `/opt/freedriver-secrets/mosquitto/acl`. Git keeps `mosquitto/acl.template` only. Compose does **not** mount the git template as live (`mosquitto.conf` uses `acl_file /mosquitto/config/secrets/acl` on the existing `/opt/freedriver-secrets/mosquitto` bind). Do not ship `__INSTANCE_ID__` or any fake id as the compose-mounted ACL.

Copy-paste apply (Techops, root/sudo, **after** Security locks the mint):

```
INSTANCE_ID=877b33d0-6e53-4212-a53f-52107383eec2 ./scripts/provision-mosquitto.sh
```

(`./scripts/provision-mosquitto.sh --instance-id 877b33d0-6e53-4212-a53f-52107383eec2` is the same.)

That **one write** puts exact topics into `/opt/freedriver-secrets/mosquitto/acl` and drops leftover `freedriver/v1/home/...` in the same step:

- `freedriver/v1/877b33d0-6e53-4212-a53f-52107383eec2/appliances`
- `freedriver/v1/877b33d0-6e53-4212-a53f-52107383eec2/commands`

It does not write a checkout/git ACL. A later deploy cannot rsync the live ACL back to a placeholder.

**Techops runs the script and restarts mosquitto.** The script does **not** restart by itself. Quarkus does **not** SSH or restart the broker. GitHub Actions must not run the secrets path — deploy only `mkdir`/`chown`s `/opt/freedriver-storage/mosquitto` (uid 1883). CI may run `--acl-only` into a temp file; it must not treat the repo template as the live ACL.

The script is idempotent for pass/cert files (never overwrites existing ones). The broker will not start until the secrets ACL exists.

## Autonomy connect (after ACL is live)

Autonomy (home) talks to the public broker. Quarkus never uses this hostname.

| | Autonomy (home) |
| --- | --- |
| Host | `mqtt.freedriver.io` |
| Port | `8883` (MQTTS). No WebSockets. No plaintext 1883. |
| User | `autonomy` |
| Password | Ask Techops, or read `/opt/freedriver-secrets/mosquitto/autonomy.pass` on the VPS. **Never put that password in this doc.** |
| TLS | **Must verify.** No skip-verify. No disabled hostname or chain checks. |
| Cert pin | Current self-signed `mqtt.freedriver.io` SHA-256 `7A:B6:6D:AF:98:3D:15:94:8C:B9:F4:13:7C:AB:B1:CC:8A:B4:ED:8A:EF:90:E7:51:71:B9:2B:6C:09:9C:87:0A` until Let's Encrypt replaces it |
| Topic A (publish) | `freedriver/v1/877b33d0-6e53-4212-a53f-52107383eec2/appliances` |
| Topic B (subscribe) | `freedriver/v1/877b33d0-6e53-4212-a53f-52107383eec2/commands` |
| Retain | `false` on both |
| QoS | `1` (client convention; the broker does not enforce it) |
| `live-commands` | stays `false` |

JSON and handshake: [autonomy-mqtt.md](autonomy-mqtt.md). Portal REST: [appliances.md](appliances.md).

## Broker

- Image: `eclipse-mosquitto:2.1.2-alpine`
- Compose publishes host 8883. Home IP is treated as dynamic; 8883 is world-reachable with broker passwords and exact-topic ACLs.
- Sysadmin still needs `mqtt.freedriver.io` A → `138.197.90.42`.
- Auth: v1 one house shares broker users `autonomy` and `api` (not Keycloak). No anonymous. Exact-topic only — no `+`/`#`. `api` is not a wildcard superuser. Each later instance gets its own autonomy user — do not share `autonomy` across instances.
- Persistence: `/opt/freedriver-storage/mosquitto`.
- Not command-live until #27.

## Secrets (not in git)

| Path | What |
| --- | --- |
| `/opt/freedriver-secrets/mosquitto/autonomy.pass` | autonomy password |
| `/opt/freedriver-secrets/mosquitto/api.pass` | api password |
| `/opt/freedriver-secrets/mosquitto/passwd` | hashed broker passwd file |
| `/opt/freedriver-secrets/mosquitto/acl` | **live** exact-topic ACL (Techops apply; not in git) |
| `/opt/freedriver-secrets/mosquitto/server.crt` | TLS cert |
| `/opt/freedriver-secrets/mosquitto/server.key` | TLS key |

## Topics

Exact topics only. No wildcards (`+`, `#`), no `$SYS`, no `freedriver/v1/#`. Never `freedriver/v1/+/appliances` or `freedriver/v1/+/commands`.

| Topic | Writer | Reader |
| --- | --- | --- |
| `freedriver/v1/{instanceId}/appliances` | that instance's autonomy user | api |
| `freedriver/v1/{instanceId}/commands` | api | that instance's autonomy user |

`instanceId` is the UUID topic segment. `instanceName` is UX-only and is never a topic segment or ACL. Boards stay off MQTT.

v1 is one house: shared `autonomy` + `api`, exact-topic only. `api` is not a wildcard superuser.

**retain=false on both topics for v1.** The broker cannot forbid retain; publishers must set it. Do not retain appliances.

## TLS

Verify the server certificate. No skip-verify.

Until Let's Encrypt replaces it, pin the current self-signed `mqtt.freedriver.io` cert SHA-256:

```
7A:B6:6D:AF:98:3D:15:94:8C:B9:F4:13:7C:AB:B1:CC:8A:B4:ED:8A:EF:90:E7:51:71:B9:2B:6C:09:9C:87:0A
```

When LE is live, trust the public chain as usual and drop this pin.

## Quarkus

On the compose network, connect to hostname `mosquitto` port 8883. **Never** use `mqtt.freedriver.io` from Quarkus — that name is for public/home clients.

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
