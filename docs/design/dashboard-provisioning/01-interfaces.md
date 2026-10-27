# Interfaces, contracts, and code flow

Dashboard provisioning of one Autonomy house. A portal-admin creates the house from Instances. House provision mints a lowercase uuid and a password, the Keycloak user stage creates the user whose username is that uuid, and the instance store keeps the house after Keycloak accepts that user. The page shows the password from that response. Opening the house again mints a new password and shows that password. The flow is the page, the mint, the Keycloak user, and the store.

`instanceId` is the house key already used on topics. `InstanceView.instanceName` stays the appliance tab label from MQTT state. The optional list name here is `label`.

## Stages

1. **Instances page.** Owns the portal-admin create form, the list, the opened house, and the copy control. It sends `CreateInstance` and `OpenInstance` to house provision and renders `InstanceList`, `ProvisionedInstance`, and `InstanceFailure`. The copy control writes `instanceId` and `password` to the clipboard and leaves both visible in the opened row. The page ends at that copy.
2. **House provision.** Owns the portal-admin gate for this flow, the mint of `House.id` and `password`, and the order of the later stages. It calls the Keycloak user stage, then writes the instance store when that stage returns `KeycloakUserResult`. On open it mints a new password and hands `ResetPassword` to the Keycloak user stage.
3. **Keycloak user.** Owns the broker allow for one house. The allow is the Keycloak user whose username is the minted lowercase uuid, with the two topic attributes on that user and membership in `mqtt-instance`. Membership is the group. The topic grant is the user attributes `mqtt_publ_topic` and `mqtt_subs_topic`.

   The broker image is `eclipse-mosquitto:2.1.2`. Acceptance is that `iotinga/mosquitto-oauth-plugin` loads on that version. Both listeners keep the certificate each one already presents, under the single auth setting the broker already uses for the pair. The plugin reads `mqtt_acl.publish` and `mqtt_acl.subscribe`. One client scope and its aggregate mappers fill those claims from `mqtt_publ_topic` and `mqtt_subs_topic`.

   `mqtt-instance` is the connection gate, together with the portal broker group `portal-broker`. The broker's Keycloak client issues a token to a user in either group. The plugin reads the claims above. A house user's attributes are the exact topics `freedriver/v1/<uuid>/appliances` for publish and `freedriver/v1/<uuid>/commands` for subscribe. `MqttBrokers.assertExactTopic` is the rule for those two. Each house user carries its own pair. `mqtt-instance` and `portal-broker` carry membership, so a topic on either group would reach every member.

   The dashboard connects as a service account, the Keycloak service account of the broker client. Human realm users stay on the portal. That service account publishes `freedriver/v1/+/commands` and subscribes to `freedriver/v1/+/appliances`. This is one authenticated account that can write every house's commands. Its attributes sit on that account. `mqtt_acl.superuser` on a house token and on this account is false.

   The plugin takes the Keycloak username and password at MQTT connect and exchanges them at the token endpoint. It records `mqtt_acl.publish` and `mqtt_acl.subscribe` on that MQTT connection. The grant lasts until disconnect, so a short access token leaves the connected house up. The broker client secret stays in server configuration. The house response is the uuid and the password.

   `#321` mints a new instance uuid for the live house, creates the Keycloak user for that uuid, and installs that id and its password on hakobune. The id already in the live ACL stays on that ACL. `#321` is the cutover that starts the broker on the plugin, in the same change as that new uuid. The broker starts with the plugin loaded, and the password file stays in place until that start is the running broker.

4. **Instance store.** Owns the `instances` collection. A document is a `House`. The store writes `id`, the optional `label`, `autonomyUsername`, and `keycloakUserId`. The password lives on the Keycloak credential and on `ProvisionedInstance`.

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
| instances | The stored houses as `InstanceSummary`. `instanceId` is `House.id`. |
| csrfToken | The CSRF token for the following create and open. The page sends it back as `X-CSRF-Token`. |

### ProvisionedInstance

From house provision to the Instances page after create and after open.

| Field | Contract |
| --- | --- |
| instanceId | Lowercase canonical UUID. The topic segment and the Keycloak username. |
| label | The stored label, or absent. |
| password | The password house provision minted for this response. Create returns the password just set. Open returns the password just set. |

`password` is 32 bytes from `SecureRandom`, encoded as unpadded URL-safe Base64, so it is one password token.

### InstanceFailure

From house provision to the Instances page when create or open stops.

| Field | Contract |
| --- | --- |
| type | `instance`. |
| cause | `label`, `broker`, or `missing`. |
| instanceId | The opened id when `cause` is `missing`. Absent for `label` and `broker`. |

The page shows one fixed sentence for `cause`: `label` is "The name is too long.", `broker` is "The house could not be saved.", and `missing` is "That house is not in the list."

`label` is HTTP 400. `broker` is HTTP 502. `missing` is HTTP 404. The role gate is `portal-admin`, and the existing 403 is every other role. A create or open that fails the CSRF check is the existing 400 from the CSRF filter. A create or open the rate limiter refuses is the existing 429.

### KeycloakUser

From house provision to the Keycloak user stage.

| Field | Contract |
| --- | --- |
| instanceId | The minted lowercase UUID. |
| username | Equal to `instanceId`. |
| password | The minted password. |
| publishTopic | `ApplianceSchemas.appliancesTopic(instanceId)`. |
| subscribeTopic | `ApplianceSchemas.commandsTopic(instanceId)`. |
| group | `mqtt-instance`. |

`publishTopic` is the user's `mqtt_publ_topic`. `subscribeTopic` is the user's `mqtt_subs_topic`. Each attribute has one value. The aggregate mapper writes the claim as a list, so one topic is a one-element list.

### KeycloakUserResult

From the Keycloak user stage to house provision. Returned when the user, both attributes, and `mqtt-instance` membership are all in place.

| Field | Contract |
| --- | --- |
| keycloakUserId | The Keycloak user id from the create. |
| username | Equal to `instanceId`. |

A create that stops short of that trio deletes the user the stage just created. House provision then ends as `InstanceFailure` cause `broker`, and the store is unchanged.

### ResetPassword

From house provision to the Keycloak user stage on open.

| Field | Contract |
| --- | --- |
| keycloakUserId | The id stored on the house. |
| password | The new password house provision just minted. Keycloak stores it as the user's credential. |

Keycloak's answer carries the acceptance. The password in this table is the one the page shows. The previous credential is replaced.

### DeleteKeycloakUser

From house provision to the Keycloak user stage after a store insert fails.

| Field | Contract |
| --- | --- |
| keycloakUserId | The id from `KeycloakUserResult`. |

### House

From house provision to the instance store, and from the store back to house provision.

| Field | Contract |
| --- | --- |
| id | Document identity. Lowercase canonical UUID. `HouseRepository.lowercaseUuid` accepts this form. The Keycloak username is this string. |
| label | Optional list name. |
| autonomyUsername | The same string as `id`. |
| keycloakUserId | The id from `KeycloakUserResult`. |

### MqttConnect

From the plugin to the broker client's token endpoint, at MQTT connect.

| Field | Contract |
| --- | --- |
| username | The Keycloak username. A house connects as `instanceId`. The dashboard connects as the broker client's service account. |
| password | The password for that username. A house uses the password the page showed for that response. The service account uses the password in server configuration. |

## Messages

The page, house provision, and the instance store hand values directly. Keycloak is the message boundary: the Admin API for a house, and the token endpoint for a connection.

1. **KeycloakUser.** Direction: house provision to the Keycloak user stage. Payload: `KeycloakUser`. Sender: house provision. Receiver: the Keycloak Admin API, as the instances admin client. One create. The response that advances the flow is `KeycloakUserResult`.
2. **ResetPassword.** Direction: house provision to the Keycloak user stage. Payload: `ResetPassword`. Sender: house provision. Receiver: the Keycloak Admin API, same admin client. One call on open. Acceptance advances the flow.
3. **DeleteKeycloakUser.** Direction: house provision to the Keycloak user stage. Payload: `DeleteKeycloakUser`. Sender: house provision. Receiver: the Keycloak Admin API. Sent when the store insert fails after `KeycloakUserResult`. One attempt. The operator's outcome is `InstanceFailure` cause `broker`. The collection is the one from before this create. A failed delete leaves that Keycloak user in place, and the next create mints a new uuid.
4. **MqttConnect.** Direction: the plugin to the broker client's token endpoint. Payload: `MqttConnect`. Sender: the plugin, with the username and password from the MQTT connect. Receiver: the token endpoint. The broker client secret stays in server configuration. The access token carries `mqtt_acl.publish` and `mqtt_acl.subscribe` for that user. The plugin keeps those lists on the MQTT connection until disconnect.

The list, open, and create handoffs below are direct. Each one carries the named type from one stage to the next.

## Handoffs

### Create

1. The Instances page hands `CreateInstance` to house provision. The handoff advances when the caller holds `portal-admin`, the `X-CSRF-Token` header matches the CSRF cookie, and the rate limiter accepts that caller. Success is HTTP 201 with `ProvisionedInstance`.
2. House provision trims `label`. A label of at most 64 characters continues. A longer label ends the flow as `InstanceFailure` cause `label`. The Keycloak user stage and the store run for a label that fits.
3. House provision mints `instanceId` with `UUID.randomUUID()` (lowercase canonical, version 4) and mints `password`. A minted id that is already stored, or a username Keycloak already has, is minted once more. A second collision ends the flow as `InstanceFailure` cause `broker`. The Keycloak user stage runs for a free id.
4. House provision hands `KeycloakUser` to the Keycloak user stage. The message is the Admin API create.
5. The Keycloak user stage hands `KeycloakUserResult` back when the user, the two attributes, and `mqtt-instance` are in place. A refusal ends as `InstanceFailure` cause `broker`. The store insert runs after `KeycloakUserResult`.
6. On `KeycloakUserResult`, house provision hands `House` to the instance store. The store insert is the next step.
7. A store insert that fails sends `DeleteKeycloakUser` and ends as `InstanceFailure` cause `broker`. The operator receives that failure. The collection stays as it was before this create.
8. A store insert that succeeds hands `ProvisionedInstance` to the page. The page shows `instanceId` and `password` for copy.

### List

The Instances page asks house provision for the list. House provision reads the instance store and hands back `InstanceList`. The caller holds `portal-admin`. Success is HTTP 200. Summaries carry `instanceId` and `label`. `csrfToken` is on this response so create and open can send it.

### Open

The Instances page hands `OpenInstance` to house provision. The handoff is `POST` and advances when the caller holds `portal-admin`, the `X-CSRF-Token` header matches the CSRF cookie, and the rate limiter accepts that caller. House provision loads one `House`. An empty load ends as `InstanceFailure` cause `missing`. A stored house whose `keycloakUserId` is blank ends the same way. A loaded house mints a new `password`, hands `ResetPassword` to the Keycloak user stage, and on acceptance hands `ProvisionedInstance` to the page. Success is HTTP 200. A refused reset ends as `InstanceFailure` cause `broker`, and the credential Keycloak already held stays the credential.

### Copy

The opened `ProvisionedInstance`, including the one just created, is what the operator copies. The copy control writes `instanceId` and `password` to the clipboard and leaves both visible in the row. Copy stays on the page.

### Connect

A house client connects with `MqttConnect.username` equal to `instanceId` and the password from the page response that set it. The dashboard connects with the service account username and the service account password from server configuration. The plugin exchanges that pair at the token endpoint. The broker client issues the token when the user is in `mqtt-instance` or `portal-broker`. The plugin records the topic lists for the life of the MQTT connection. The dashboard's session subscribes and publishes for the houses in the instance store, under the one service account that can write every house's commands.

### Log line

A log line for this flow carries `instanceId` and the outcome `created`, `reset`, or `refused`. The password lives on the Keycloak credential and, for the response that minted it, on `ProvisionedInstance`. The broker client secret and the instances admin client secret live in server configuration.
