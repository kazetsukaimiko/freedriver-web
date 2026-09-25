# Autonomy MQTT how-to (v1)

How autonomy (home) talks to Freedriver’s Mosquitto broker. Follow this without guessing topics, JSON, or TLS.

This is **not** the portal REST/BFF/OIDC essay. Portal product surface: [appliances.md](appliances.md). Broker/ops notes: [mqtt-connect.md](mqtt-connect.md).

**Out of scope for autonomy:** OIDC, UX, enabling live-commands, owning Mosquitto.

**live-commands stays `false`.** Autonomy may still connect, subscribe, and publish state. Quarkus will not publish Topic B in production until [freedriver-web#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27). Treat incoming commands as optional until then.

## Contract home (do not use the closed suite PR)

MQTT v1 types live in `io.freedriver:freedriver-mqtt-contract` (`io.freedriver.mqtt.contract`), owned and published by this portal. Consume path: [mqtt-contract-consume.md](mqtt-contract-consume.md). Topic JSON is also in [appliances.md](appliances.md).

Do **not** copy `Appliance` / `ApplianceStateMessage` / `ApplianceCommandMessage` / `ApplianceSchemas` / `ApplianceJson` into autonomy. Extra JSON fields are rejected. There is no `schemaVersion`. The wire field is `applianceName`, not `name`. The switch field is `state`, not `on`. `instanceId` is a UUID **topic segment only**. `instanceName` is UX-only.

Do **not** depend on `io.freedriver.autonomy:autonomy-mqtt-contract`. Do **not** depend on `io.freedriver:mqtt-contract` from the Freedriver library suite, [freedriver#18](https://github.com/kazetsukaimiko/freedriver/issues/18), or the closed [freedriver#19](https://github.com/kazetsukaimiko/freedriver/pull/19). kaze rejected putting mqtt-contract in that suite.

## Connect

| | Autonomy (home) | Quarkus (`api`) |
| --- | --- | --- |
| Host | `mqtt.freedriver.io:8883` | compose hostname `mosquitto:8883` |
| TLS | MQTTS — **must verify** against the public CA (no skip-verify). Do not pin a leaf fingerprint. | MQTTS on the docker network |
| User | `autonomy` (this instance only) | `api` (exact-topic for this instance) |
| Auth | broker password, not Keycloak | broker password, not Keycloak |

Quarkus **never** uses `mqtt.freedriver.io`. That name is for home/autonomy only.

Protocol: MQTT only. No WebSockets. No plaintext 1883.

### TLS

Let's Encrypt is live on `mqtt.freedriver.io:8883`. Verify the server certificate against the public CA. Do not disable hostname or chain checks. No skip-verify. Do not pin a leaf fingerprint.

The `mosquitto-cert-sync` sidecar copies Caddy’s cert onto the broker (`scripts/sync-mosquitto-le-cert.sh`) — see [mqtt-connect.md](mqtt-connect.md). Do not copy certs or passwords into this doc or into git.

### Passwords

Broker passwords live on the VPS at `/opt/freedriver-secrets/mosquitto/*.pass` (`autonomy.pass`, `api.pass`). **Ask Techops** (or read that path). Do not put secrets in this repo or in issues.

v1 one house: shared `autonomy` + `api` users, exact-topic only. `api` is not a wildcard superuser. No `+`/`#` bootstrap. A later instance gets its own autonomy user — do not share `autonomy` across instances.

## Topics

One broker can carry more than one autonomy instance. Interpolate `instanceId` (UUID hex + hyphens). Version nibbles are not checked. No wildcards (`+`, `#`), no `$SYS`, no `freedriver/v1/#`. Never `freedriver/v1/+/appliances` or `.../commands`. `instanceName` is never a topic segment or ACL. Boards stay off MQTT.

Long-term, **freedriver-web owns minting `instanceId`**. First house is not an admin screen. Quarkus does not mint for v1 apply. The house does **not** mint the first id.

First-house `instanceId` (UUID hex+hyphens; do not enforce a v4 nibble): `877b33d0-6e53-4212-a53f-52107383eec2`.

Use these exact topics (retain=false, QoS 1, `live-commands` stays `false`):

- `freedriver/v1/877b33d0-6e53-4212-a53f-52107383eec2/appliances`
- `freedriver/v1/877b33d0-6e53-4212-a53f-52107383eec2/commands`

A display name for the first house will live in the portal/DB later. That name is UX-only — never a topic segment, never in ACL/compose/code.

First-house apply is Techops + `/opt/freedriver-secrets/mosquitto/acl`. Mint is locked; `877b33d0-6e53-4212-a53f-52107383eec2` is the live first-house instanceId. The apply command on [mqtt-connect.md](mqtt-connect.md) is the repeatable procedure (idempotent; do not invent another UUID).

| | Topic | Publisher | Subscriber | QoS | Retain |
| --- | --- | --- | --- | --- | --- |
| A (state) | `freedriver/v1/{instanceId}/appliances` | that instance's `autonomy` | `api` | 1 | **false** |
| B (commands) | `freedriver/v1/{instanceId}/commands` | `api` | that instance's `autonomy` | 1 | **false** always |

The broker cannot forbid retain. Publishers must set retain=false. Do not retain the appliance map (Quarkus liveness is receive-time; a retained map would lie after a restart). `live-commands` stays `false`.

## Topic A — state (autonomy → Quarkus)

Publish QoS 1, retain=false.

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

There is **no** `instanceId` in the body (it is the topic). There is **no** separate `id` and no `name`. Each appliance is `{applianceName, state}` only. Boards are not on this wire.

`applianceName` is the existing autonomy alias key (`AliasView.applianceStates`). It is not a new slug. Portal `POST /api/appliances/{instanceId}/{applianceName}` uses that same string.

`instanceId` is a UUID topic segment, not the MQTT protocol client-id. Version nibbles are not checked. `instanceName` is the dashboard tab label only.

When no command produced this map, send `"appliedCommandId": null`.

Field rules (Quarkus rejects otherwise):

- `instanceName`: non-blank UX label
- `applianceName`: autonomy alias key, 1–64 characters (not blank)
- `state`: boolean
- extra JSON fields: rejected (including `instanceId`, `on`, `name`, `id`, `schemaVersion`, board fields)

## Topic B — command (Quarkus → autonomy)

Subscribe QoS 1. Messages are retain=false. Until live-commands is on, you may see **no** traffic here; still subscribe so you are ready.

```json
{
  "commandId": "550e8400-e29b-41d4-a716-446655440000",
  "applianceName": "hallway",
  "state": false
}
```

`applianceName` is the same alias key as Topic A. There is no `name` and no `applianceId`.

Quarkus **mints** `commandId`. Autonomy never invents it.

## commandId / appliedCommandId handshake

1. Quarkus publishes Topic B with a new `commandId`.
2. Autonomy applies the flip (or the latest-per-alias rule below).
3. Autonomy publishes the next Topic A with `appliedCommandId` set to that same id.

Quarkus waits (API side, default 5s) for a valid Topic A whose `appliedCommandId` matches. If it never arrives, the API reports timeout and does **not** pretend the flip worked.

If you publish a periodic map with no command applied, use `appliedCommandId: null`.

## lastUpdated / stale (API only — not in MQTT)

Do **not** put `lastUpdated` or `stale` on Topic A.

- `lastUpdated` is when **Quarkus received** a valid Topic A (ISO-8601 UTC). It is not autonomy’s clock.
- **Stale** = no valid Topic A in **20 seconds**, or never received.

After a Quarkus restart the map starts stale until the next **live** Topic A. That is why retain=false.

## Reconnect

QoS 1 can deliver a backlog after a disconnect. **Do not apply a pile of old commands.**

Either:

- apply **latest-per-alias** (`applianceName`) only, or
- drop commands older than the **20s** stale window.

## What you implement vs what you do not

| Do | Do not |
| --- | --- |
| Connect as `autonomy` to `mqtt.freedriver.io:8883` with TLS verify against the public CA | Skip TLS verify, disable hostname/chain checks, or pin a leaf fingerprint |
| Publish Topic A, subscribe Topic B for `877b33d0-6e53-4212-a53f-52107383eec2` | Publish Topic B, subscribe Topic A, or invent another `instanceId` |
| Echo `appliedCommandId` on the next map | Depend on a closed Freedriver library suite PR |
| Ask Techops for `/opt/freedriver-secrets/mosquitto/autonomy.pass` | Put secrets in the doc or invent a Maven Central version |
| Speak to the broker after Techops applies | Run the VPS apply, flip `live-commands`, or open 1883 |
