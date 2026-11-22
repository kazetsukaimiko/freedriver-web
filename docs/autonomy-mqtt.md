# Autonomy MQTT how-to (v1)

How autonomy (home) talks to Freedriver’s Mosquitto broker: topics, JSON, and TLS in one place.

Portal product surface: [appliances.md](appliances.md). Broker, ACL, and apply: [mqtt-connect.md](mqtt-connect.md).

**Autonomy scope:** connect, subscribe to commands, publish state. The portal owns OIDC, UX, `live-commands`, and Mosquitto.

**live-commands is on in production (MVP).** Quarkus publishes Topic B. Autonomy subscribes and applies those commands.

## Contract home

MQTT v1 types live in `io.freedriver:freedriver-mqtt-contract` (`io.freedriver.mqtt.contract`), owned and published by this portal. Consume path: [mqtt-contract-consume.md](mqtt-contract-consume.md). Autonomy depends on that artifact for `Appliance`, `ApplianceStateMessage`, `ApplianceCommandMessage`, `ApplianceSchemas`, and `ApplianceJson`. kaze keeps mqtt-contract in this portal, separate from the Freedriver library suite ([freedriver#18](https://github.com/kazetsukaimiko/freedriver/issues/18), closed [freedriver#19](https://github.com/kazetsukaimiko/freedriver/pull/19)).

The repo module also holds `ApplianceCommandList`, `CommandListEntry` (`ApplianceCommandEntry` and `CancelCommandEntry`), `ScheduledCommandsMessage`, and `PendingCommand`. The published 1.7.0 jar is the five types in the sentence above. Those new records ship on the next release-please bump of `version.txt`, and [kazetsukaimiko/autonomy#101](https://github.com/kazetsukaimiko/autonomy/issues/101) pins that version. `ApplianceCommandMessage` and `ApplianceSchemas.commandsTopic` are `@Deprecated` with `since` empty in this change. The release that publishes the jar writes `since`, and that value is the new version.

## Connect

| | Autonomy (home) | Quarkus (`api`) |
| --- | --- | --- |
| Host | `mqtt.freedriver.io:8883` | compose hostname `mosquitto:8883` |
| TLS | MQTTS, public Let's Encrypt certificate | MQTTS, internal certificate `DNS:mosquitto` only |
| User | `autonomy` (this instance only) | `api` (exact topics for this instance) |
| Auth | broker password | broker password |

### TLS

Let's Encrypt is live on `mqtt.freedriver.io:8883` (host port 8883, the public listener). Verify that certificate with hostname and chain checks, using the public CA as the trust anchor. Compose hostname `mosquitto` port 8883 is a different socket and presents only `DNS:mosquitto`. Autonomy uses the public name, not that internal certificate.

### Passwords

Broker passwords live on the VPS at `/opt/freedriver-secrets/mosquitto/*.pass` (`autonomy.pass`, `api.pass`). **Ask Techops** for `autonomy.pass` and the first-house instanceId (or read that path), and connect once Techops has applied the ACL.

## Topics

One broker can carry more than one autonomy instance. Each topic carries `instanceId` (a lowercase UUID, any version) as its segment. Appliances and commands are exact instance topics; metrics uses `freedriver/v1/{instanceId}/metrics/#` for autonomy and `freedriver/v1/+/metrics/#` for api. Boards stay inside the instance.

Long-term, **freedriver-web owns minting `instanceId`**. The first-house instanceId is locked. This doc writes it as `__INSTANCE_ID__`.

Use these topics. The live grants are in `mosquitto/acl.template`.

| | Topic | Publisher | Subscriber | QoS | Retain |
| --- | --- | --- | --- | --- | --- |
| A (state) | `freedriver/v1/__INSTANCE_ID__/appliances` | that instance's `autonomy` | `api` via `freedriver/v1/+/appliances` | 1 | **false** |
| B (commands) | `freedriver/v1/__INSTANCE_ID__/commands` | `api` via `freedriver/v1/+/commands` | that instance's `autonomy` | 1 | **false** |
| C (command lists) | `freedriver/v1/__INSTANCE_ID__/command-lists` | `api` via `freedriver/v1/+/command-lists` | that instance's `autonomy` | 1 | **false** |
| D (scheduled) | `freedriver/v1/__INSTANCE_ID__/scheduled` | that instance's `autonomy` | `api` via `freedriver/v1/+/scheduled` | 1 | **false** |
| Metrics | `freedriver/v1/__INSTANCE_ID__/metrics/#` | that instance's `autonomy` | `api` via `freedriver/v1/+/metrics/#` | 1 | **false** |

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

Topic B carries command traffic in production. Autonomy subscribes and applies those commands.

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "applianceName": "hallway",
  "state": false
}
```

`applianceName` is the same alias key as Topic A.

Quarkus **mints** `commandId`.

While `live-commands` is on, Topic B stays the command topic `autonomy` and `api` use. Keep publishing and subscribing there through this change.

## Topic C — command list (Quarkus → autonomy)

`ApplianceCommandList` rides `freedriver/v1/__INSTANCE_ID__/command-lists` at QoS 1 with retain false, the same publication settings as Topic B. The broker sets `retain_available false`, so a retained publish is refused on this topic too. The broker grants `api` write of `freedriver/v1/+/command-lists` and that instance's `autonomy` read of the exact topic. Dashboard publish of the list is #637. Topic B remains the command topic.

```json
{
  "listId": "00000000-0000-4000-8000-000000000001",
  "issuedAt": "2026-11-19T15:04:05Z",
  "commands": [
    {
      "type": "appliance",
      "id": "00000000-0000-4000-8000-000000000002",
      "target": "hallway",
      "state": true
    },
    {
      "type": "appliance",
      "id": "00000000-0000-4000-8000-000000000003",
      "target": "hallway",
      "state": false,
      "delay": "PT5M"
    },
    {
      "type": "cancel",
      "id": "00000000-0000-4000-8000-000000000004",
      "target": "porch"
    }
  ]
}
```

`listId`, every entry `id`, and a `cancelId` are canonical UUID strings: `UUID.fromString(s).toString().equals(s)`, lowercase 8-4-4-4-12. The sender mints a fresh UUID for each. Entry ids are unique inside the list, and `listId` differs from every entry id. `issuedAt` is an ISO-8601 instant (`Instant.parse`). The list holds 1 to 32 entries.

An `appliance` entry is `{id, target, state, delay?}`. `target` is 1 to 64 characters. `state` is a JSON boolean. `delay` is an ISO-8601 duration: absent or any zero (`PT0S`, `P0D`, written back as `PT0S`) means now, and any other value runs from `PT1S` through 86400 seconds (`ApplianceSchemas.MAX_DELAY_SECONDS`), fractions included (`PT1.5S`). `P1D` is written back as `PT24H`. The bound is `Duration.compareTo`. One appliance entry per target and delay; an absent delay matches `PT0S`. On now plus off after `PT5M` for one target is one list. The delay counts from receipt.

A `cancel` entry names exactly one of `target` (every pending delayed command for that alias) or `cancelId` (one pending entry's id). It carries neither `state` nor `delay`. A JSON `false` or `"PT0S"` counts as present and the list is rejected.

Every entry, cancels included, is processed in list order.

- Cancel target `hallway`, then `hallway` off in `PT5M`: one pending entry remains.
- `hallway` off in `PT5M`, then cancel target `hallway`: the pending set is empty.

Admission runs before any switch or timer. The controller computes the pending set that the whole list produces, in order. A cancel frees its slot for a later entry in the same list. When that set exceeds `ApplianceSchemas.MAX_PENDING` (128) or repeats a pending id, the list is refused whole: the previous pending set stays, and Topic A carries the previous `appliedCommandId`. The dashboard check against its stored snapshot (#637) is advisory. The controller's count is the one that applies.

Once the whole list is processed, the controller publishes Topic A with `appliedCommandId` set to `listId`. That echo covers a list of only cancels and a list of only delayed entries. When a delayed entry fires, Topic A carries `appliedCommandId` set to that entry's id. A fresh canonical id, distinct from `listId`, means a `cancelId` and a fired entry's `appliedCommandId` each name one entry.

QoS 1 can redeliver a list. Drop a list whose `issuedAt` is more than 20 seconds before or after receipt. The controller remembers applied `listId`s across a restart for at least 40 seconds. A replay leaves the switches, the timers, and the pending set as they were.

## Topic D — scheduled snapshot (autonomy → Quarkus)

`ScheduledCommandsMessage` is the controller's complete pending set on `freedriver/v1/__INSTANCE_ID__/scheduled`, QoS 1, retain false. The controller publishes it at start, on every change, and with each state map. The broker grants that instance's `autonomy` write of the exact topic and `api` read of `freedriver/v1/+/scheduled`. `pending: []` means the pending set is empty. The pending set survives a controller restart, and the start snapshot carries it.

```json
{
  "publishedAt": "2026-11-19T15:04:05Z",
  "pending": [
    {
      "id": "00000000-0000-4000-8000-000000000003",
      "target": "hallway",
      "state": false,
      "dueAt": "2026-11-19T15:09:05Z"
    }
  ]
}
```

A pending entry is `{id, target, state, dueAt}`. `id` is a canonical UUID, unique in the snapshot. `target` is 1 to 64 characters. `state` is a JSON boolean. `dueAt` and `publishedAt` are ISO-8601 instants. The snapshot holds at most 128 entries.

## Grants (#636)

`mosquitto/acl.template` keeps the appliances, commands, and metrics lines and adds the four grants below. `assert_acl_topics` accepts the new exact lines only for `autonomy` and only in the direction in the table. The new `api` lines are write `freedriver/v1/+/command-lists` and read `freedriver/v1/+/scheduled`. `apply_instance_acl` exempts those `api` lines from the instance-id check along with the three `api` lines already there. `.github/workflows/ci.yml` accepts `+` or `#` on the `api` grants and on autonomy `metrics/#`.

Password ACL plane, in `mosquitto/acl.template`, beside the appliances, commands, and metrics lines:

| User | Access | Filter |
| --- | --- | --- |
| `autonomy` | read | `freedriver/v1/__INSTANCE_ID__/command-lists` |
| `autonomy` | write | `freedriver/v1/__INSTANCE_ID__/scheduled` |
| `api` | write | `freedriver/v1/+/command-lists` |
| `api` | read | `freedriver/v1/+/scheduled` |

`api` is the broker user with `freedriver.mqtt.username=api` and password file `api.pass`.

Keycloak JWT plane, separate from the password ACL. `service-account-freedriver-mqtt` stays on the JWT path and in `portal-broker`. `scripts/provision-keycloak-freedriver.sh` sets `mqtt_publ_topic` to `[freedriver/v1/+/commands, freedriver/v1/+/command-lists]` and `mqtt_subs_topic` to `[freedriver/v1/+/appliances, freedriver/v1/+/scheduled]`. `ensure_instances_admin` and `ensure_mqtt_broker` mark those two attributes multivalued. `label` and `mqtt_superuser` stay single-valued, and `mqtt_superuser` stays false.

A new house receives `mqtt_publ_topic` `[appliancesTopic(id), scheduledTopic(id)]` and `mqtt_subs_topic` `[commandsTopic(id), commandListsTopic(id)]` from `KeycloakInstanceIdentity`, derived from the house uuid. Read-back accepts either order when each attribute is that pair. A shorter list, a longer list, a repeated value, or another house's topic deletes the new user. `MqttBrokers.assertExactTopic` rejects `+` and `#` on every house value. The same provision walks `mqtt-instance` and converts a house that still has the previous one-topic pair. A house with any other topic set stays as it is, and the script names that username when it exits. Groups `mqtt-instance` and `portal-broker` keep their membership, and their attribute maps omit topic keys.

The v1 house also reaches the new topics through broker user `autonomy` on the password plane.

## commandId / appliedCommandId handshake

1. Quarkus publishes Topic B with a new `commandId`.
2. Autonomy applies the flip (or the latest-per-alias rule below).
3. Autonomy publishes the next Topic A with `appliedCommandId` set to that same id.

The portal POST completes when that Topic A arrives (wait and timeout: [appliances.md](appliances.md#lastupdated--stale--timeout)). Topic B's handshake stays on that command's `commandId`. The `listId` and entry-id echoes apply to a command-lists message. Autonomy subscribes to the exact command-lists topic.

## lastUpdated / stale

`lastUpdated` and `stale` are portal API fields that Quarkus computes from receive time: [appliances.md](appliances.md#lastupdated--stale--timeout). Topic A carries the fields listed above.

## Reconnect

QoS 1 can deliver a backlog after a disconnect. Apply only current commands. Either:

- apply **latest-per-alias** (`applianceName`) only, or
- drop commands older than the **20s** stale window.
