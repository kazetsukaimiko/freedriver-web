# Autonomy MQTT how-to (v1)

How autonomy (home) talks to Freedriver’s Mosquitto broker: topics, JSON, and TLS in one place.

Portal product surface: [appliances.md](appliances.md). Broker, ACL, and apply: [mqtt-connect.md](mqtt-connect.md).

**Autonomy scope:** connect, subscribe to commands, publish state. The portal owns OIDC, UX, `live-commands`, and Mosquitto.

**live-commands stays `false`.** Quarkus publishes Topic B in production once [freedriver-web#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27) lands. Until then, treat incoming commands as optional.

## Contract home

MQTT v1 types live in `io.freedriver:freedriver-mqtt-contract` (`io.freedriver.mqtt.contract`), owned and published by this portal. Consume path: [mqtt-contract-consume.md](mqtt-contract-consume.md). Autonomy depends on that artifact for `Appliance`, `ApplianceStateMessage`, `ApplianceCommandMessage`, `ApplianceSchemas`, and `ApplianceJson`. kaze keeps mqtt-contract in this portal, separate from the Freedriver library suite ([freedriver#18](https://github.com/kazetsukaimiko/freedriver/issues/18), closed [freedriver#19](https://github.com/kazetsukaimiko/freedriver/pull/19)).

## Connect

| | Autonomy (home) | Quarkus (`api`) |
| --- | --- | --- |
| Host | `mqtt.freedriver.io:8883` | compose hostname `mosquitto:8883` |
| TLS | MQTTS, verified against the public CA | MQTTS on the docker network |
| User | `autonomy` (this instance only) | `api` (exact topics for this instance) |
| Auth | broker password | broker password |

### TLS

Let's Encrypt is live on `mqtt.freedriver.io:8883`. Verify the server certificate with hostname and chain checks, using the public CA as the trust anchor.

### Passwords

Broker passwords live on the VPS at `/opt/freedriver-secrets/mosquitto/*.pass` (`autonomy.pass`, `api.pass`). **Ask Techops** for `autonomy.pass` and the first-house instanceId (or read that path), and connect once Techops has applied the ACL.

## Topics

One broker can carry more than one autonomy instance. Each topic carries `instanceId` (a lowercase UUID, any version) as its segment. ACLs grant exact topics only. Boards stay inside the instance.

Long-term, **freedriver-web owns minting `instanceId`**. The first-house instanceId is locked. This doc writes it as `__INSTANCE_ID__`.

Use these exact topics:

| | Topic | Publisher | Subscriber | QoS | Retain |
| --- | --- | --- | --- | --- | --- |
| A (state) | `freedriver/v1/__INSTANCE_ID__/appliances` | that instance's `autonomy` | `api` | 1 | **false** |
| B (commands) | `freedriver/v1/__INSTANCE_ID__/commands` | `api` | that instance's `autonomy` | 1 | **false** |

QoS 1 is a client convention, and retain is a publisher setting. retain=false keeps every map Quarkus sees live, because Quarkus liveness is receive-time.

## Topic A — state (autonomy → Quarkus)

```json
{
  "instanceName": "Cabin",
  "appliedCommandId": "550e8400-e29b-41d4-a716-446655440000",
  "appliances": [
    {
      "applianceName": "hallway",
      "state": true
    }
  ]
}
```

The body has exactly these fields: `instanceName`, `appliedCommandId`, and `appliances`, where each appliance is `{applianceName, state}`. `instanceId` is the topic segment.

`applianceName` is the existing autonomy alias key (`AliasView.applianceStates`). Portal `POST /api/appliances/{instanceId}/{applianceName}` uses that same string.

`instanceName` is the dashboard tab label, UX-only. The MQTT protocol client-id is independent of `instanceId`.

For a periodic map, send `"appliedCommandId": null`.

Field rules (Quarkus rejects any other shape):

- `instanceName`: non-blank UX label
- `applianceName`: autonomy alias key, 1–64 characters
- `state`: boolean

## Topic B — command (Quarkus → autonomy)

Topic B carries traffic once live-commands is on; subscribe now so you are ready.

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "applianceName": "hallway",
  "state": false
}
```

`applianceName` is the same alias key as Topic A.

Quarkus **mints** `commandId`.

## commandId / appliedCommandId handshake

1. Quarkus publishes Topic B with a new `commandId`.
2. Autonomy applies the flip (or the latest-per-alias rule below).
3. Autonomy publishes the next Topic A with `appliedCommandId` set to that same id.

The portal POST completes when that Topic A arrives (wait and timeout: [appliances.md](appliances.md#lastupdated--stale--timeout)).

## lastUpdated / stale

`lastUpdated` and `stale` are portal API fields that Quarkus computes from receive time: [appliances.md](appliances.md#lastupdated--stale--timeout). Topic A carries the fields listed above.

## Reconnect

QoS 1 can deliver a backlog after a disconnect. Apply only current commands. Either:

- apply **latest-per-alias** (`applianceName`) only, or
- drop commands older than the **20s** stale window.
