# Appliance map and command API

Portal REST/product surface for the appliance map. **Live MQTT command publish is not enabled.**
This is blocked on [#25](https://github.com/kazetsukaimiko/freedriver-web/issues/25) (Keycloak admin + Grafana lockdown) being Done and Security sign-off on [#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27).

The browser never speaks MQTT. Quarkus is the only MQTT client, and only on the docker-network Mosquitto broker when live commands are later turned on. Never `mqtt.freedriver.io`.

This page is the portal REST contract. Broker connect, TLS, passwords, and ACL: [autonomy-mqtt.md](autonomy-mqtt.md). Wire types: [mqtt-contract-consume.md](mqtt-contract-consume.md) (`io.freedriver:freedriver-mqtt-contract`).

`ApplianceControl` is the one router, keyed by `instanceId`. Mock event sources and a later MQTT client fire/observe the same CDI bus. There is no fake/disabled/live `ApplianceBackend`.

## REST

Roles: `dashboard` **or** `portal-admin`. Not `realm-admin`. Not the old role `admin`.

CORS allowlist: `https://app.freedriver.io` only (dev may also allow localhost).

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/api/appliances` | — | 200 `{ "instances": [ ... ] }` |
| POST | `/api/appliances/{instanceId}/{applianceName}` | `{ "on": false }` | 200 that instance |

`instanceId` is a UUID. `applianceName` is the alias. Extra JSON fields on POST are rejected. The browser sends `{ "on": bool }` only. `commandId` is minted in Quarkus. HTTP keeps `on`; MQTT uses `state`.

GET:

```json
{
  "instances": [
    {
      "instanceId": "550e8400-e29b-41d4-a716-446655440000",
      "instanceName": "Cabin",
      "lastUpdated": "2026-08-26T00:00:00Z",
      "stale": false,
      "timeout": false,
      "appliances": [
        { "applianceName": "hallway", "on": false }
      ]
    }
  ]
}
```

No instances yet:

```json
{ "instances": [] }
```

Each `instanceName` is a dashboard tab. GET does not return a CSRF token.

### Status codes

| Case | GET | POST |
| --- | --- | --- |
| No session | 401 | 401 |
| Wrong role | 403 | 403 |
| Extra JSON fields | — | 400 |
| Unknown `instanceId` | — | 404, no command |
| Unknown `applianceName` | — | 404, no command |
| Known instance, stale | 200, that instance `stale: true` | 409, no command |
| Confirmed | — | 200, `timeout: false` |
| Wait expired | — | 200, `timeout: true`, last map unchanged |
| Rate limited | — | 429 |
| Feature disabled (prod default) | 404 | 404 |

GET is never 409.

After a Quarkus restart the map is empty until the next state event. Do not combine a retained Topic A with receive-time liveness.

## MQTT JSON

One broker can carry more than one autonomy instance. Isolation is `instanceId` on the **topic** (UUID hex + hyphens), not a JSON field, not a board, not the MQTT client-id. Version nibbles are not checked. `instanceName` is JSON/UX only — not in the topic, not in an ACL. Git keeps `mosquitto/acl.template` only; the live broker ACL is `/opt/freedriver-secrets/mosquitto/acl`. First-house apply is Techops + that secrets file (the house mints `instanceId`; kaze may hand it). Do not invent a UUID in git. No portal paste UI. See [mqtt-connect.md](mqtt-connect.md).

| | Topic | Retain | QoS |
| --- | --- | --- | --- |
| A (state) | `freedriver/v1/{instanceId}/appliances` | `false` (do not retain) | 1 |
| B (commands) | `freedriver/v1/{instanceId}/commands` | `false` always | 1 |

Exact topic strings only. Extra JSON fields are rejected. Allowed appliance fields are `applianceName` and `state`.

### Topic A — state (autonomy → Quarkus)

```json
{
  "instanceName": "Cabin",
  "appliedCommandId": "uuid-or-null",
  "appliances": [
    {
      "applianceName": "hallway",
      "state": true
    }
  ]
}
```

- `instanceId`: topic segment only
- `instanceName`: UX/dashboard tab label only
- `applianceName`: existing autonomy alias key, 1–64 characters, not blank
- `state`: boolean (primitive; not `on`)
- `appliedCommandId`: waiter handshake, or `null`. Not stored on the portal snapshot.
- Boards stay inside the instance; they are not MQTT topics

### Topic B — command (Quarkus → autonomy)

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "applianceName": "hallway",
  "state": false
}
```

`applianceName` is the same alias key as Topic A. `instanceId` is the topic. Quarkus mints `commandId`. Until live-commands is on, Topic B has no production traffic.

Reconnect / latest-per-alias: [autonomy-mqtt.md](autonomy-mqtt.md). kaze owns that behavior.

## BFF / session

Quarkus owns the OIDC code flow (`application-type=web-app`). The browser gets an HTTP-only, Secure, SameSite=Lax session cookie (Lax so the Keycloak return from auth.freedriver.io to app.freedriver.io still has a session). It is not readable from JS. The confidential client secret is `QUARKUS_OIDC_CREDENTIALS_SECRET` only — never `webui`.

`commandId` is minted in Quarkus. The browser only sends `{ "on": bool }`. Appliance fetches send `X-Requested-With: XMLHttpRequest` so a missing session is 401, not a login HTML redirect.

OIDC stays **off** in the default profile (`quarkus.oidc.enabled=false`) until #27. Map/command are still fail-closed: no session → 401, wrong role → 403 (`dashboard` or `portal-admin`). GET `/api/appliances` does not return `csrfToken`. POST does not require `X-CSRF-Token`.

**CSRF** (when OIDC is later flipped on): POST `/api/appliances/{instanceId}/{applianceName}` will require `X-CSRF-Token` matching a `csrfToken`. That is not current behavior. SameSite=Lax is not a CSRF substitute.

## lastUpdated / stale / timeout

- `lastUpdated` is when **Quarkus received** a valid Topic A payload (ISO-8601 UTC). It is not autonomy's clock.
- **Stale** = no state in 20 seconds, or never received.
- POST waits up to 5 seconds (`FREEDRIVER_COMMAND_TIMEOUT`, default `5s`, hard-capped at 30s) for a state event whose `appliedCommandId` matches the command we just issued. Waiters live in `ApplianceControl`, not on the snapshot. It does **not** wait for a 10s pulse.
- On timeout: HTTP 200 + last map + `timeout: true`. Never pretend the flip worked.
- Audit log (no secrets): who / when / appliance / command / `confirmed` or `timeout`.

## Production safety

Default / prod properties:

```
freedriver.appliances.enabled=false
freedriver.appliances.live-commands=false
freedriver.appliances.mock=false
quarkus.oidc.enabled=false
```

`./mvnw -pl app -am quarkus:dev` uses **mock-autonomy** on the same `ApplianceControl` bus (`%dev`): no broker, no Keycloak, `/api/hello` and `/api/health` stay 200, `/api/appliances` is served from the mock event source (one Cabin instance, six named appliances).

### One `%dev` auth path

Authorization stays **on** in `%dev`. The only open-auth type is `DevOpenAuthAugmentor` in `io.freedriver.app.security`, gated with `@IfBuildProfile("dev")` (never test or prod). It grants principal `dev` and role `dashboard` to anonymous callers so `@RolesAllowed` actually runs. There is no Keycloak.

Do **not** add a second escape:

- no `DevAuthMechanism` (or any other auth type) under `io.freedriver.app.appliances`
- no `freedriver.appliances.auth-required` knob
- no `%dev.quarkus.security.auth.enabled-in-dev-mode=false`

`AppliancesDisabledFilter` stays in appliances. That is the feature-off URL tree (404), not auth.

`%test` uses `@TestSecurity`. Unauthenticated calls are 401; wrong role is 403. The augmentor is not in the test build.

The mock event source is not what ships in prod. The live MQTT adapter (`MqttLiveClient`) is compiled and talks to the same `ApplianceControl` bus. It is created only when `live-commands=true`. Default/prod keeps that flag false, so it does not connect. Host is compose-network `mosquitto:8883` with TLS; it refuses `mqtt.freedriver.io` and port 1883. Exact instance topics only (no `+`/`#`). Instance ids come from `FREEDRIVER_MQTT_INSTANCE_IDS`, not git.

Live command route is **not** Done. Blocked on #25 and #27. `live-commands` stays `false`.
