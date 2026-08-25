# Appliance map and command API

Product contract for the home appliance map. **Live MQTT command publish is not enabled.**
This is blocked on [#25](https://github.com/kazetsukaimiko/freedriver-web/issues/25) (Keycloak admin + Grafana lockdown) being Done and Security sign-off on [#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27).

The browser never speaks MQTT. Quarkus is the only MQTT client, and only on the docker-network Mosquitto broker when live commands are later turned on. Never `mqtt.freedriver.io`.

Autonomy MQTT how-to for kaze: [autonomy-mqtt.md](autonomy-mqtt.md). This page is the portal REST/product surface. MQTT Java types are `io.freedriver.autonomy:autonomy-mqtt-contract:2026-08_r51`, not `io.freedriver.app.appliances`.

## Topics

One broker can carry more than one autonomy instance. Isolation is `instanceId` (a UUID), not a board and not the MQTT client-id.

| | Topic | Retain | QoS |
| --- | --- | --- | --- |
| A (state) | `freedriver/v1/{instanceId}/appliances` | `false` (do not retain) | 1 |
| B (commands) | `freedriver/v1/{instanceId}/commands` | `false` always | 1 |

`instanceName` is the dashboard label only. It is in JSON, not in the topic, and not in an ACL. Extra JSON fields are rejected. There is no `schemaVersion`.

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

- appliances are `{applianceName, on}` only — no separate `id`, no `name`
- `instanceId`: UUID (hex + hyphens) on the topic and in JSON. Not the MQTT client-id. Version nibble is not checked.
- `instanceName`: UX/dashboard label only
- `applianceName`: existing autonomy alias key, not a new slug
- `on`: boolean
- `appliedCommandId`: UUID of the command that produced this map, or `null`
- Boards stay inside the instance; they are not MQTT topics
- Portal `POST /api/appliances/{id}` uses that same `applianceName` string; do not invent a second identifier

### Topic B — command (Quarkus → autonomy)

```json
{
  "instanceId": "550e8400-e29b-41d4-a716-446655440000",
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "applianceName": "living-room-lamp",
  "on": false
}
```

No `name`. No `applianceId`. `applianceName` is the same alias key as Topic A. `instanceId` must match the instance that owns the appliance.

## REST

Roles: `dashboard` **or** `portal-admin`. Not `realm-admin`. Not the old name `admin`.

CORS allowlist: `https://app.freedriver.io` only (dev may also allow localhost).

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/api/appliances` | — | 200 `{ lastUpdated, stale, timeout:false, appliances }` |
| POST | `/api/appliances/{id}` | `{ "on": false }` | 200 same shape (`{id}` is the Topic A `applianceName` / alias key) |

`lastUpdated` / `stale` / `timeout` are REST-only. They are not on the MQTT wire.

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

GET is never 409. `lastUpdated` is `null` if we have never received a state.

After a Quarkus restart the map starts stale until the next **live** Topic A. Do not combine retained Topic A with receive-time liveness.

## BFF / session

Quarkus owns the OIDC code flow. The browser gets an HTTP-only, Secure, SameSite=Lax session cookie (Lax so the Keycloak return from auth.freedriver.io to app.freedriver.io still has a session). It is not readable from JS. The confidential client secret never goes in `webui`.

`commandId` is minted in Quarkus. The browser only sends `{ "on": bool }`.

Live OIDC stays off until #24/#25. When it is on:

- Session cookie: HTTP-only, Secure, SameSite=Lax (Strict would drop the session on the Keycloak callback)
- POST `/api/appliances/{id}` requires `X-CSRF-Token` matching the `csrfToken` from GET `/api/appliances`. Do not rely on SameSite alone.
- API still checks session + (`dashboard` or `portal-admin`)


## lastUpdated / stale / timeout

- `lastUpdated` is when **Quarkus received** a valid Topic A payload (ISO-8601 UTC). It is not autonomy's clock.
- **Stale** = no state in 20 seconds, or never received.
- POST waits up to 5 seconds (`FREEDRIVER_COMMAND_TIMEOUT`, default `5s`, hard-capped at 30s) for a Topic A whose `appliedCommandId` matches the command we just issued. It does **not** wait for a 10s pulse.
- On timeout: HTTP 200 + last map + `timeout: true`. Never pretend the flip worked.
- Audit log (no secrets): who / when / appliance / command / `confirmed` or `timeout`.

## Reconnect (autonomy)

On reconnect, autonomy must **not** apply a pile of old QoS 1 commands. Use latest-per-alias (`applianceName`), or drop commands older than the 20s stale window. kaze owns that behavior.

## Production safety

Default / prod properties:

```
freedriver.appliances.enabled=false
freedriver.appliances.live-commands=false
freedriver.appliances.backend=none
quarkus.oidc.enabled=false
```

`./mvnw quarkus:dev` uses the **fake** backend (`%dev`): no broker, no Keycloak, `/api/hello` and `/api/health` stay 200, `/api/appliances` is served from an in-process fixture.

The fake backend is not what ships in prod. The live MQTT path is compiled as contract helpers only; it is not a CDI bean, does not connect, and refuses `mqtt.freedriver.io`. When live is later enabled, Quarkus must use the compose-network hostname (`mosquitto`), never the public broker.

Live command route is **not** Done. Blocked on #25 and #27.
