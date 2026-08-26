# Appliance map and command API

Portal REST/product surface for the appliance map. **Live MQTT command publish is not enabled.**
This is blocked on [#25](https://github.com/kazetsukaimiko/freedriver-web/issues/25) (Keycloak admin + Grafana lockdown) being Done and Security sign-off on [#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27).

The browser never speaks MQTT. Quarkus is the only MQTT client, and only on the docker-network Mosquitto broker when live commands are later turned on. Never `mqtt.freedriver.io`.

This page is the portal REST contract. Broker connect, TLS, passwords, and ACL: [autonomy-mqtt.md](autonomy-mqtt.md). Maven pin `io.freedriver.autonomy:autonomy-mqtt-contract:2026-08_r51`: [mqtt-contract-consume.md](mqtt-contract-consume.md).

## REST

Roles: `dashboard` **or** `portal-admin`. Not `realm-admin`. Not the old role `admin`.

CORS allowlist: `https://app.freedriver.io` only (dev may also allow localhost).

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/api/appliances` | — | 200 map below |
| POST | `/api/appliances/{id}` | `{ "on": false }` | 200 same shape |

Path `{id}` is the alias `applianceName`. It is not `instanceId`. Do not invent a second identifier.

GET and POST success JSON (REST only — not on the MQTT wire):

```json
{
  "lastUpdated": "2026-08-26T00:00:00Z",
  "stale": false,
  "timeout": false,
  "appliances": [
    {
      "applianceName": "living-room-lamp",
      "on": true
    }
  ]
}
```

Each appliance is `{applianceName, on}` only. The map does not include `instanceId` or `instanceName`. Extra JSON fields on POST are rejected. The browser sends `{ "on": bool }` only. `commandId` is minted in Quarkus.

`lastUpdated` is `null` if we have never received a state:

```json
{
  "lastUpdated": null,
  "stale": true,
  "timeout": false,
  "appliances": []
}
```

GET does not return a CSRF token.

### Status codes

| Case | GET | POST |
| --- | --- | --- |
| No session | 401 | 401 |
| Wrong role | 403 | 403 |
| Extra JSON fields | — | 400 |
| Unknown appliance `applianceName` (path `{id}` is that alias) | — | 404, no command |
| Stale or never received | 200, `stale: true` | 409, no command |
| Confirmed (`appliedCommandId` matches) | — | 200, `timeout: false`, map updated |
| Wait expired | — | 200, `timeout: true`, **last map unchanged** |
| Rate limited | — | 429 |
| Feature disabled (prod default) | 404 | 404 |

GET is never 409.

After a Quarkus restart the map starts stale until the next **live** Topic A. Do not combine a retained Topic A with receive-time liveness.

## MQTT JSON (`2026-08_r51`)

One broker can carry more than one autonomy instance. Isolation is `instanceId` (UUID hex + hyphens), not a board and not the MQTT client-id. Version nibbles are not checked. `instanceName` is JSON/UX only — not in the topic, not in an ACL.

| | Topic | Retain | QoS |
| --- | --- | --- | --- |
| A (state) | `freedriver/v1/{instanceId}/appliances` | `false` (do not retain) | 1 |
| B (commands) | `freedriver/v1/{instanceId}/commands` | `false` always | 1 |

Exact topic strings only. Extra JSON fields are rejected. Allowed appliance fields are `applianceName` and `on`.

### Topic A — state (autonomy → Quarkus)

```json
{
  "instanceId": "550e8400-e29b-41d4-a716-446655440000",
  "instanceName": "Cabin",
  "appliedCommandId": "uuid-or-null",
  "appliances": [
    {
      "applianceName": "living-room-lamp",
      "on": true
    }
  ]
}
```

- `instanceId`: UUID (hex + hyphens) on the topic and in JSON. Not the MQTT client-id
- `instanceName`: UX/dashboard label only
- `applianceName`: existing autonomy alias key (`AliasView.applianceStates`), 1–64 characters, not blank
- `on`: boolean
- `appliedCommandId`: UUID of the command that produced this map, or `null`
- Boards stay inside the instance; they are not MQTT topics

### Topic B — command (Quarkus → autonomy)

```json
{
  "instanceId": "550e8400-e29b-41d4-a716-446655440000",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "applianceName": "living-room-lamp",
  "on": false
}
```

`applianceName` is the same alias key as Topic A. `instanceId` must match the instance that owns the appliance. Quarkus mints `commandId`. Until live-commands is on, Topic B has no production traffic.

Reconnect / latest-per-alias: [autonomy-mqtt.md](autonomy-mqtt.md). kaze owns that behavior.

## BFF / session

Quarkus owns the OIDC code flow. The browser gets an HTTP-only, Secure, SameSite=Lax session cookie (Lax so the Keycloak return from auth.freedriver.io to app.freedriver.io still has a session). It is not readable from JS. The confidential client secret never goes in `webui`.

`commandId` is minted in Quarkus. The browser only sends `{ "on": bool }`.

OIDC is off. GET `/api/appliances` does not return `csrfToken`. POST does not require `X-CSRF-Token`.

**Future-BFF** ([#26](https://github.com/kazetsukaimiko/freedriver-web/issues/26) / [#58](https://github.com/kazetsukaimiko/freedriver-web/issues/58)): when OIDC is on, POST `/api/appliances/{id}` will require `X-CSRF-Token` matching a `csrfToken`. That is not current behavior. Do not treat today's GET map as if it already includes `csrfToken`. SameSite=Lax is not a CSRF substitute.

When OIDC is on, the API still checks session + (`dashboard` or `portal-admin`).

## lastUpdated / stale / timeout

- `lastUpdated` is when **Quarkus received** a valid Topic A payload (ISO-8601 UTC). It is not autonomy's clock.
- **Stale** = no state in 20 seconds, or never received.
- POST waits up to 5 seconds (`FREEDRIVER_COMMAND_TIMEOUT`, default `5s`, hard-capped at 30s) for a Topic A whose `appliedCommandId` matches the command we just issued. It does **not** wait for a 10s pulse.
- On timeout: HTTP 200 + last map + `timeout: true`. Never pretend the flip worked.
- Audit log (no secrets): who / when / appliance / command / `confirmed` or `timeout`.

## Production safety

Default / prod properties:

```
freedriver.appliances.enabled=false
freedriver.appliances.live-commands=false
freedriver.appliances.backend=none
quarkus.oidc.enabled=false
```

`./mvnw quarkus:dev` uses **mock-autonomy** (`%dev`, `backend=mock`): no broker, no Keycloak, `/api/hello` and `/api/health` stay 200, `/api/appliances` is served from the CDI mock backend. The mock house is one instance (`instanceId` + UX `instanceName`) and exactly six named appliances (`hallway`, `kitchen`, `living-room`, `bedroom`, `garage`, `porch`) — no boards, no board UUID. Turn mock off with properties only (`freedriver.appliances.backend=none` or `freedriver.appliances.enabled=false`).

### One `%dev` auth path

Authorization stays **on** in `%dev`. The only open-auth type is `DevOpenAuthAugmentor` in `io.freedriver.app.security`, gated with `@IfBuildProfile("dev")` (never test or prod). It grants principal `dev` and role `dashboard` to anonymous callers so `@RolesAllowed` actually runs. There is no Keycloak.

Do **not** add a second escape:

- no `DevAuthMechanism` (or any other auth type) under `io.freedriver.app.appliances`
- no `freedriver.appliances.auth-required` knob
- no `%dev.quarkus.security.auth.enabled-in-dev-mode=false`

`AppliancesDisabledFilter` stays in appliances. That is the feature-off URL tree (404), not auth.

`%test` uses `@TestSecurity`. Unauthenticated calls are 401; wrong role is 403. The augmentor is not in the test build.

mock-autonomy is not what ships in prod (`backend=none`). Incoming state is a CDI event the mock backend observes (`lastUpdated` = receive time). Command publish fires the same bus; mock observes the command and fires a new state with `appliedCommandId`. The later MQTT client (#40) hooks this same bus — do not invent a second observe/publish path. The live MQTT path is compiled as contract helpers only; it is not a CDI bean, does not connect, and refuses `mqtt.freedriver.io`. When live is later enabled, Quarkus must use the compose-network hostname (`mosquitto`), never the public broker.

Live command route is **not** Done. Blocked on #25 and #27. `live-commands` stays `false`.
