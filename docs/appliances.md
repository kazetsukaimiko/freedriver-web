# Appliance map and command API

Portal REST/product surface for the appliance map. **Live MQTT command publish turns on after [#25](https://github.com/kazetsukaimiko/freedriver-web/issues/25) (Keycloak admin + Grafana lockdown) is Done and Security signs off on [#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27).**

The browser speaks REST. Quarkus is the portal's MQTT client, connecting to the docker-network Mosquitto broker once live commands are on.

This page is the portal REST contract. MQTT topics, JSON, TLS, and passwords: [autonomy-mqtt.md](autonomy-mqtt.md). Broker, ACL, and first-house apply: [mqtt-connect.md](mqtt-connect.md). Wire types: [mqtt-contract-consume.md](mqtt-contract-consume.md) (`io.freedriver:freedriver-mqtt-contract`).

`ApplianceControl` is the one router, keyed by `instanceId`. Mock event sources and the MQTT client fire/observe the same CDI bus.

## REST

Roles: `dashboard` **or** `portal-admin`.

CORS allowlist: `https://app.freedriver.io` (dev adds localhost origins).

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| GET | `/api/appliances` | — | 200 `{ "instances": [ ... ], "csrfToken": "..." }` |
| POST | `/api/appliances/{instanceId}/{applianceName}` | `{ "on": false }` plus `X-CSRF-Token` | 200 that instance |

`instanceId` is a UUID. `applianceName` is the alias. The POST body is exactly `{ "on": bool }`. `commandId` is minted in Quarkus. HTTP keeps `on`; MQTT uses `state`.

GET:

```json
{
  "csrfToken": "…",
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

Before any instance reports:

```json
{ "csrfToken": "…", "instances": [] }
```

Each `instanceName` is a dashboard tab. GET returns `csrfToken`. POST requires `X-CSRF-Token` matching the HttpOnly `freedriver-csrf` cookie. A bad or missing token is 400 with an empty body; that one switch fails and the session continues.

### Status codes

| Case | GET | POST |
| --- | --- | --- |
| No session | 401 | 401 |
| Wrong role | 403 | 403 |
| Extra JSON fields | — | 400 |
| Missing or wrong CSRF | — | 400, command dropped, empty body |
| Unknown `instanceId` | — | 404, command dropped |
| Unknown `applianceName` | — | 404, command dropped |
| Known instance, stale | 200, that instance `stale: true` | 409, command dropped |
| Confirmed | — | 200, `timeout: false` |
| Wait expired | — | 200, `timeout: true`, last map unchanged |
| Rate limited | — | 429 |
| Feature disabled (prod default) | 404 | 404 |

After a Quarkus restart the map is empty until the next state event.

## MQTT

Topics, JSON, retain, and the `commandId` / `appliedCommandId` handshake: [autonomy-mqtt.md](autonomy-mqtt.md).

On the portal side:

- `instanceId` is the topic segment and the REST path key. `instanceName` is the dashboard tab label.
- `appliedCommandId` completes the POST waiter.
- kaze owns reconnect / latest-per-alias behavior.

## BFF / session

Quarkus owns the OIDC code flow (`application-type=web-app`). The browser gets an HTTP-only, Secure, SameSite=Lax session cookie (Lax so the Keycloak return from auth.freedriver.io to app.freedriver.io keeps the session). The confidential client secret is `QUARKUS_OIDC_CREDENTIALS_SECRET` on the server.

Appliance fetches send `X-Requested-With: XMLHttpRequest`, so a missing session returns 401 to the fetch.

Map and command are fail-closed (status table above). CSRF protection is the `X-CSRF-Token` check. `quarkus.oidc.enabled` flips on in a later card, after CSRF (#98).

## lastUpdated / stale / timeout

- `lastUpdated` is when **Quarkus received** a valid Topic A payload (ISO-8601 UTC).
- **Stale** = more than 20 seconds since the last valid state, or before the first one.
- POST waits up to 5 seconds (`FREEDRIVER_COMMAND_TIMEOUT`, default `5s`, hard-capped at 30s) for a state event whose `appliedCommandId` matches the command just issued. Waiters live in `ApplianceControl`.
- Audit log records who / when / appliance / command / `confirmed` or `timeout`.

## Production safety

Default / prod properties:

```
freedriver.appliances.enabled=false
freedriver.appliances.live-commands=false
freedriver.appliances.mock=false
quarkus.oidc.enabled=false
```

`./mvnw -pl app -am quarkus:dev` runs **mock-autonomy** in-process on the same `ApplianceControl` bus (`%dev`): `/api/hello` and `/api/health` return 200, and `/api/appliances` serves the mock event source (one Cabin instance, six named appliances).

### One `%dev` auth path

Authorization stays **on** in `%dev` (`quarkus.security.auth.enabled-in-dev-mode` keeps its default, true). `DevOpenAuthAugmentor` in `io.freedriver.app.security` is the single open-auth type, gated with `@IfBuildProfile("dev")`. It grants principal `dev` and role `dashboard` to anonymous callers so `@RolesAllowed` runs. Appliance auth is `@RolesAllowed` in every profile.

`AppliancesDisabledFilter` in appliances owns the feature-off URL tree (404).

`%test` uses `@TestSecurity`.

Live MQTT is `io.freedriver:freedriver-mqtt` plus `freedriver-mqtt-paho`. The app CDI adapter (`MqttLiveClient`) talks to the same `ApplianceControl` bus, connects when `live-commands=true`, and fails startup when `live-commands` and `mock` are both true. `MqttBrokers` requires a docker-network host (`freedriver.io` names throw), port 8883, TLS, and exact topics. `freedriver.mqtt` is a mapped object (host, port, tls, username). Host and env vars: [mqtt-connect.md](mqtt-connect.md#quarkus).
