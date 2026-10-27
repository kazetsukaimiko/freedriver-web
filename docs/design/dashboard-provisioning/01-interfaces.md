# Interfaces, contracts, and code flow

Dashboard provisioning of one Autonomy house. A portal-admin creates the house from Instances. House provision mints the instance id and that house's broker secret, the broker allow stage allows that house, and the instance store keeps the house after the broker allows it. The operator copies the secret from the page. The flow is the page, the mint, the allow for one new house, and the store.

`instanceId` is the house key already used on topics. `InstanceView.instanceName` stays the appliance tab label from MQTT state. The optional list name here is `label`.

## Stages

1. **Instances page.** Owns the portal-admin create form, the list, the opened house, and the copy control. It sends `CreateInstance` and `OpenInstance` to house provision and renders `InstanceList`, `ProvisionedInstance`, and `InstanceFailure`.
2. **House provision.** Owns the portal-admin gate for this flow, the mint of `instanceId` and `brokerSecret`, and the order of the later stages. It calls broker allow, then writes the instance store when the allow succeeds.
3. **Broker allow.** Owns one allow exchange for a new house: `InstanceAllow`, `InstanceAllowResult`, and `InstanceAllowRollback`. It uses the admin login from server configuration. The autonomy user and the api user are the subjects of the grants.
4. **Instance store.** Owns the `instances` collection. A document is an `InstanceRecord`. The store writes a record when house provision hands it one, and it returns a record on list and on open.

## Types

Fields are the contract. Types that cross a stage boundary are the only ones listed.

### CreateInstance

From the Instances page to house provision.

| Field | Contract |
| --- | --- |
| label | Optional. Trimmed. Absent when blank. At most `ApplianceSchemas.NAME_MAX` (64) characters. List name only. |

### OpenInstance

From the Instances page to house provision.

| Field | Contract |
| --- | --- |
| instanceId | The stored house id. The edge parses it as a UUID. The stored form is lowercase canonical. |

### InstanceSummary

Carried inside `InstanceList`.

| Field | Contract |
| --- | --- |
| instanceId | Lowercase canonical UUID. |
| label | The stored label, or absent. |

### InstanceList

From house provision to the Instances page.

| Field | Contract |
| --- | --- |
| instances | The stored houses as `InstanceSummary`, newest mint first. |
| csrfToken | The CSRF token for the following create. The page sends it back as `X-CSRF-Token`. |

### ProvisionedInstance

From house provision to the Instances page after create and after open.

| Field | Contract |
| --- | --- |
| instanceId | Lowercase canonical UUID. The topic segment. |
| label | The stored label, or absent. |
| autonomyUsername | The same string as `instanceId`. The broker username for that house. |
| brokerSecret | The secret house provision minted for that username. |

### InstanceFailure

From house provision to the Instances page when create or open stops.

| Field | Contract |
| --- | --- |
| type | `instance`. |
| cause | `label`, `broker`, or `missing`. |
| instanceId | The opened id when `cause` is `missing`. Absent for `label` and `broker`. |

`label` is HTTP 400. `broker` is HTTP 502. `missing` is HTTP 404. A caller outside `portal-admin` is the existing 403. A create that fails the CSRF check is the existing 400 from the CSRF filter. A create the rate limiter refuses is the existing 429.

### InstanceAllow

From house provision to broker allow.

| Field | Contract |
| --- | --- |
| instanceId | The minted lowercase UUID. |
| autonomyUsername | Equal to `instanceId`. |
| brokerSecret | The minted secret. |
| autonomyRole | `autonomy-` plus `instanceId`. |
| appliancesTopic | `ApplianceSchemas.appliancesTopic(instanceId)`. |
| commandsTopic | `ApplianceSchemas.commandsTopic(instanceId)`. |
| apiUsername | The configured api username. The live value is `api`. |
| apiRole | The configured role already attached to that api user. |
| correlation | `instanceId`. |

The allow outcome for those two topics:

- The autonomy user writes `appliancesTopic` and reads `commandsTopic`.
- The api user reads `appliancesTopic` and writes `commandsTopic`.
- Each topic is one exact segment path. `MqttBrokers.assertExactTopic` is the exact-topic rule.
- The grants are `appliancesTopic` and `commandsTopic`.
- `correlation` repeats `instanceId`. `brokerSecret` is the autonomy user's password on this allow.

`brokerSecret` is 32 bytes from `SecureRandom`, encoded as URL-safe Base64 without padding, so it is one password token.

### InstanceAllowResult

From broker allow to house provision.

| Field | Contract |
| --- | --- |
| correlation | The `instanceId` from the allow. |
| allowed | True when every grant in the exchange was accepted. |
| partial | True when the broker applied at least one grant and then stopped, or when the response never arrived after the allow was published. |
| cause | Broker error text when `allowed` is false. House provision keeps this text inside the allow stage. |

### InstanceAllowRollback

From house provision to broker allow. Sent when `partial` is true.

| Field | Contract |
| --- | --- |
| instanceId | The minted id. |
| autonomyUsername | Equal to `instanceId`. |
| autonomyRole | The role from the allow. |
| appliancesTopic | The appliances topic from the allow. |
| commandsTopic | The commands topic from the allow. |
| apiRole | The api role from the allow. |

The rollback removes that autonomy user, that autonomy role, and that house's grants on the api role. The api user remains.

### InstanceRecord

From house provision to the instance store, and from the store back to house provision.

| Field | Contract |
| --- | --- |
| instanceId | Document identity. Lowercase canonical UUID. |
| label | Optional list name. |
| autonomyUsername | Equal to `instanceId`. |
| brokerSecret | The secret returned on `ProvisionedInstance`. |

## Messages

The page, house provision, and the instance store hand values directly. The broker is the only message boundary.

1. **InstanceAllow.** Direction: house provision to broker allow. Payload: `InstanceAllow`. Sender: house provision. Receiver: the broker's dynamic-security control topic `$CONTROL/dynamic-security/v1`. One publish, QoS 1, retain false. The admin client is the publisher. The admin username and admin secret stay in server configuration.
2. **InstanceAllowResult.** Direction: broker allow to house provision. Payload: `InstanceAllowResult`. Sender: the broker. Receiver: the admin client, subscribed to `$CONTROL/dynamic-security/v1/response`. The result that advances the flow is the one whose `correlation` is this allow's `instanceId`.
3. **InstanceAllowRollback.** Direction: house provision to broker allow. Payload: `InstanceAllowRollback`. Sender: house provision. Receiver: the same control topic, same QoS and retain. Sent when `InstanceAllowResult.partial` is true. One attempt.

The list, open, and create handoffs below are direct. Each one carries the named type from one stage to the next.

## Handoffs

### Create

1. The Instances page hands `CreateInstance` to house provision. The handoff advances when the caller holds `portal-admin`, the `X-CSRF-Token` header matches the CSRF cookie, and the rate limiter accepts that caller.
2. House provision trims `label`. A label longer than 64 characters ends the flow as `InstanceFailure` cause `label`, before broker allow and before the store.
3. House provision mints `instanceId` with `UUID.randomUUID()` (lowercase canonical, version 4) and mints `brokerSecret`. `autonomyUsername` is that `instanceId` string. A minted id that is already stored is minted once more. A second collision ends the flow as `InstanceFailure` cause `broker`, before broker allow and before the store.
4. House provision hands `InstanceAllow` to broker allow. The message is the allow publish.
5. Broker allow hands `InstanceAllowResult` back. `allowed` true advances the flow. `allowed` false with `partial` true sends `InstanceAllowRollback`, then ends as `InstanceFailure` cause `broker`. `allowed` false with `partial` false ends as `InstanceFailure` cause `broker` with the store unchanged.
6. On `allowed` true, house provision hands `InstanceRecord` to the instance store. The store insert is the next step.
7. A store insert that fails sends `InstanceAllowRollback` and ends as `InstanceFailure` cause `broker`. The operator receives that failure. The collection stays as it was before this create.
8. A store insert that succeeds hands `ProvisionedInstance` to the page. The page shows `instanceId` and `brokerSecret` for copy.

### List

The Instances page asks house provision for the list. House provision reads the instance store and hands back `InstanceList`. The caller holds `portal-admin`. Summaries carry `instanceId` and `label`. `csrfToken` is on this response so create can send it.

### Open

The Instances page hands `OpenInstance` to house provision. House provision reads one `InstanceRecord` and hands back `ProvisionedInstance`. An unknown id ends as `InstanceFailure` cause `missing`. The caller holds `portal-admin`.

### Copy

The opened `ProvisionedInstance`, including the one just created, is what the operator copies. The copy control writes `instanceId` and `brokerSecret` to the clipboard and leaves both visible in the row. Copy stays on the page.

### Log line

A log line for this flow carries `instanceId` and the allow outcome. `brokerSecret`, the admin secret, and the api password stay in the record, the allow payload, and server configuration.
