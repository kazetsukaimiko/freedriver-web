# Implementation

How document 1 is built. Names below are the names in that document.

`InstanceView` in `java/dashboard-model` stays the appliance snapshot. `ProvisionedInstance` is the new view.

## Existing code

### Instances page

`java/dashboard/src/main/webui/src/App.tsx` builds the primary nav. `primaryNavItems` adds Phone sign-in when `portalAdmin` is true, and that flag is the `GET /api/invites` result. The change adds an Instances item beside Phone sign-in, path `/instances`, under the same flag, and renders the Instances page for that path.

`java/dashboard/src/main/webui/src/App.test.tsx` covers that menu. The change covers the Instances link in the portal-admin menu.

New files beside `phones.ts`, `PhoneSignIn.tsx`, and `PhoneSignIn.test.tsx`:

| Path | Role |
| --- | --- |
| `java/dashboard/src/main/webui/src/instances.ts` | `INSTANCES_PATH`, the request and response types, and the fixed failure sentences from document 1. |
| `java/dashboard/src/main/webui/src/Instances.tsx` | The create form, the list, the opened house, and the copy control. |
| `java/dashboard/src/main/webui/src/Instances.test.tsx` | The page tests. |
| `java/dashboard/src/main/webui/src/instances.css` | Layout for the list and the password row. |

The page calls `apiFetch` the same way `phones.ts` does. Create and open send `X-CSRF-Token`.

### House provision

`InviteResource` in `java/dashboard/src/main/java/io/freedriver/app/invites/InviteResource.java` is the portal-admin resource beside this flow. The new types live in `io.freedriver.app.instances`, next to that package.

Paths under `java/dashboard/src/main/java/io/freedriver/app/instances/`:

| Path | Role |
| --- | --- |
| `InstancesApi.java` | JAX-RS interface. Path `/api/instances`. `@RolesAllowed("portal-admin")` on each method. `@Valid` and `@Size(max = ApplianceSchemas.NAME_MAX)` on `CreateInstance.label`. `@RateLimited("instances")` on create and on open. |
| `InstancesResource.java` | `@ApplicationScoped` implementation of that interface. Returns `InstanceList` and `ProvisionedInstance`. Throws the domain exceptions below. |
| `HouseProvision.java` | Plain service. Mints the id and the password, calls the Keycloak user stage, then the store. Returns `House` or throws a domain exception. |
| `InvalidLabelException.java` | Label failed the contract. |
| `BrokerRefusedException.java` | The Keycloak user stage or the following insert failed. |
| `InstanceNotFoundException.java` | Open's lookup was empty, or the stored house has a blank `keycloakUserId`. |
| `InstanceFailureMapper.java` | Maps those three exceptions to `InstanceFailure` and the HTTP status in document 1. |

`InstancesApi` carries the JAX-RS, validation, role, and rate-limit annotations. `InstancesResource` is the application-scoped implementation. `HouseProvision` returns `House` or throws a domain exception. The role check lives on `InstancesApi`. The rate limit lives on the existing `RateLimitedFilter`, keyed by the authenticated subject. The annotation value marks the method. The limiter key is that subject, the same key the other command paths use.

`CsrfFilter` in `java/dashboard/src/main/java/io/freedriver/app/security/CsrfFilter.java` already requires `X-CSRF-Token` on `POST /api/invites` and writes `csrfToken` onto `InviteList`. The change treats `POST /api/instances` and `POST /api/instances/{instanceId}/open` as command paths and writes `csrfToken` onto an `InstanceList` from `GET /api/instances`.

### Keycloak user

`KeycloakPhoneSignIn` in `java/dashboard/src/main/java/io/freedriver/app/phones/KeycloakPhoneSignIn.java` is the Admin-shaped HTTP client beside this flow. It uses the invite client. The house client uses the instances admin client and leaves the invite client on the phone path.

`MqttLiveClient`, `MqttConnectionProducer`, and `MqttSettings` in `io.freedriver.app.appliances` are the dashboard's MQTT session. `MqttConnection` and `PahoMqttConnection` are the session types. `MqttBrokers` checks the live broker and `assertExactTopic`. `ApplianceSchemas` formats the two exact topics. `MqttLog` is the logger `io.freedriver.mqtt`.

New files:

| Path | Role |
| --- | --- |
| `java/dashboard/src/main/java/io/freedriver/app/instances/InstanceIdentity.java` | Port `HouseProvision` calls. Methods accept `KeycloakUser`, `ResetPassword`, and `DeleteKeycloakUser`, and return `KeycloakUserResult` from create. |
| `java/dashboard/src/main/java/io/freedriver/app/instances/KeycloakInstanceIdentity.java` | That port on the Keycloak Admin API, same admin-URI shape as `KeycloakPhoneSignIn`. |

The instances admin client is `freedriver-instances`. Its service account holds realm-management `manage-users`. Keys, same pattern as the invite client:

| Key | Where |
| --- | --- |
| `freedriver.instances.client-id` | `application.properties`, default `freedriver-instances`. |
| `freedriver.instances.client-secret` | Environment, same pattern as `freedriver.invite.client-secret`. |

`scripts/provision-keycloak-freedriver.sh` already provisions the realm's clients. The change adds the one-time broker client, the client scope, the aggregate mappers, `mqtt-instance`, and `portal-broker` in that script. The broker client id is `freedriver-mqtt`, with client authentication and direct access grants, and the scope as a default scope. Its direct-grant flow realizes the group gate in document 1. The service account of that client is the dashboard's MQTT login `service-account-freedriver-mqtt`, in `portal-broker`, with the service-account attributes from document 1. The instances admin client is provisioned there too. House create is `KeycloakInstanceIdentity`.

Plugin configuration holds the broker client secret. The instances admin secret is the dashboard key above. `MqttSettings.username()` today defaults to `api` in `application.properties`. The change sets that default to `service-account-freedriver-mqtt`. `freedriver.mqtt.password` and `freedriver.mqtt.password-file` hold that account's password. The prod password path is the `application-prod.properties` row below.

`MqttLiveClient` today reads ids from `MqttSettings.instances()`. The change takes that list from `HouseRepository` and connects as the service account. `MqttLiveClientTest` seeds those houses. An empty store with `live-commands` on fails startup the way an empty id list fails today.

One-time broker files:

| Path | Now | Change |
| --- | --- | --- |
| `docker-compose.yml` | The `mosquitto` service image is `eclipse-mosquitto:2.1.2-alpine`. | That image is the 2.1.2 acceptance in document 1. The image carries `iotinga/mosquitto-oauth-plugin`. |
| `mosquitto/mosquitto.conf` | Two listeners, each with its certificate pair. `password_file` and `acl_file` are the current grant. Both listeners use the broker's default shared auth setting. | Points at the plugin. Listener lines realize the broker contract in document 1. |
| `scripts/provision-mosquitto.sh` | Writes one house into the password file and the ACL. | House create is `KeycloakInstanceIdentity`. This per-house grant is the one document 1's cutover retires. |
| `scripts/test-mqtt-two-sockets.sh` | Asserts the two listeners, the certificate lines, `password_file`, `acl_file`, and the shared auth setting. | Those assertions stay. The plugin-load acceptance in document 1 is the image log line that the plugin initialized. |
| `java/dashboard/src/main/resources/application-prod.properties` | Sets `freedriver.mqtt.instance-ids-file` to the ACL path and `freedriver.mqtt.password-file` to `/opt/freedriver-secrets/mosquitto/api.pass`. | The password-file line stays. The instance-ids line is removed. The session's house list is `HouseRepository`. |

`MqttLiveClient` subscribes to `ApplianceSchemas.appliancesTopic` for each house `HouseRepository` returns, and command publish stays on `ApplianceSchemas.commandsTopic`. Those exact topics sit inside the service-account grant in document 1.

`KeycloakInstanceIdentity` creates the user with the username, an enabled account, the password as a permanent credential, and the two attributes, then joins `mqtt-instance`. The user id is the last segment of the create `Location` path. A stop short of the trio in document 1 deletes that user and throws `BrokerRefusedException`. Reset calls the Admin API reset-password for `ResetPassword.keycloakUserId`. Delete calls the Admin API delete for `DeleteKeycloakUser`.

`MqttLog` gains a house line whose arguments are `instanceId` and the outcome word from document 1.

### Instance store

`House` and `HouseRepository` already sit in `io.freedriver.app.instances`. `java/dashboard/pom.xml` already depends on `quarkus-mongodb-panache`. `docker-compose.yml` already runs `mongodb` from `mongo:8.0`, with data at `/opt/freedriver-storage/mongodb`, and the dashboard already takes `QUARKUS_MONGODB_CONNECTION_STRING`. `application.properties` already sets `quarkus.mongodb.database` to `freedriver` and keeps `org.mongodb.driver` at WARN.

| Path | Now | Change |
| --- | --- | --- |
| `java/dashboard/src/main/java/io/freedriver/app/instances/House.java` | Panache entity for collection `instances`. Fields `id`, `label`, `autonomyUsername`, `brokerSecret`. `toString` renders `id`, `label`, and `autonomyUsername`. | Gains `keycloakUserId`. House provision sets `autonomyUsername` to `id` and `keycloakUserId` from `KeycloakUserResult`. A later change drops `brokerSecret`. |
| `java/dashboard/src/main/java/io/freedriver/app/instances/HouseRepository.java` | `save` and `load`. `lowercaseUuid` requires the canonical lowercase form. | Gains `list`, returning every `House`, for the list handoff and for `MqttLiveClient`. |

`HouseRepositoryTest` already saves and loads one house. The change asserts the saved house round-trips `id`, `label`, `autonomyUsername`, and `keycloakUserId`, and that `toString` renders `id`, `label`, and `autonomyUsername`.

## How document 1 is built

### Stage 1, Instances page

`Instances.tsx` is the stage. `instances.ts` is the client for the three direct handoffs.

`CreateInstance` is the JSON body of `POST /api/instances`. The page sends the `csrfToken` from the latest `InstanceList` as `X-CSRF-Token`. When the response is `ProvisionedInstance`, the page shows `instanceId` and `password`. When the response is `InstanceFailure`, the page shows the fixed sentence for `cause`.

`InstanceList` is the JSON body of `GET /api/instances`. Each `InstanceSummary` is one row. The row's open action sends `OpenInstance` as `POST /api/instances/{instanceId}/open`.

`ProvisionedInstance` from that POST is the opened house. The copy control writes `instanceId` and `password` to the clipboard and leaves both visible in the row.

`App.tsx` shows the nav item when `portalAdmin` is true. A 403 from the list renders the denied state, the same shape as `PhoneSignIn`. `App.test.tsx` and `Instances.test.tsx` pass when a portal-admin reaches the heading Instances, the menu for any other role is Dashboard alone, create renders the password from a `ProvisionedInstance` response, open renders the password from the next response, and a `cause` of `broker` renders the failure sentence while the list is unchanged.

### Stage 2, House provision

`InstancesResource` and `HouseProvision` are the stage. `InstancesApi` is the edge.

The create handoff reaches `HouseProvision` after `@RolesAllowed`, `CsrfFilter`, and `@RateLimited("instances")`. `HouseProvision` trims `label`, mints `id` and `password`, and builds `KeycloakUser`. It calls `InstanceIdentity`, then `HouseRepository.save` when the call returns `KeycloakUserResult`.

`InvalidLabelException`, `BrokerRefusedException`, and `InstanceNotFoundException` leave the resource as those types. `InstanceFailureMapper` writes `InstanceFailure`. The resource returns `ProvisionedInstance` on success. Create is HTTP 201. Open and list are HTTP 200. `instanceId` on the success body is `House.id`.

`InstancesResourceTest` lives at `java/dashboard/src/test/java/io/freedriver/app/instances/InstancesResourceTest.java`. It passes when a `portal-admin` create with a stub identity and a stub store returns `ProvisionedInstance`, a refused create leaves the stub store as it started, a label past 64 characters returns cause `label` before the stub identity is invoked, open with a stored `keycloakUserId` returns a new password, open of an unknown id returns cause `missing`, and any other role receives 403.

### Stage 3, Keycloak user

`KeycloakInstanceIdentity` is the stage. `InstanceIdentity` is the port `HouseProvision` calls, so the mint and the store stay free of the Admin client.

`KeycloakInstanceIdentity` checks `publishTopic` and `subscribeTopic` with `MqttBrokers.assertExactTopic` before the create. On a stop short of the trio, it deletes the user it created and throws `BrokerRefusedException`. On a failed store insert, `HouseProvision` calls the port with `DeleteKeycloakUser` and then throws `BrokerRefusedException`.

`KeycloakInstanceIdentityTest` lives at `java/dashboard/src/test/java/io/freedriver/app/instances/KeycloakInstanceIdentityTest.java`. It passes when the create username is the minted id, the attributes are `publishTopic` and `subscribeTopic`, the group join is `mqtt-instance`, reset sends `ResetPassword` and the password under test is the one in that request, and the log line matches the log handoff in document 1.

The one-time rows in the table above realize the broker image, `mosquitto.conf`, the broker client, the scope, and the mappers. `MqttLiveClient` realizes `MqttConnect` for the dashboard with the service account from `MqttSettings`.

### Stage 4, Instance store

`HouseRepository` and `House` are the stage. `HouseProvision` calls `save` after `KeycloakUserResult`, `list` for the list handoff, and `load` for open.

The insert is the document 1 handoff of `House`. A duplicate `id` or a Mongo failure throws, and `HouseProvision` treats that as `BrokerRefusedException` after `DeleteKeycloakUser`. List maps each `House` to an `InstanceSummary`. Open returns one house or an empty result, and `InstancesResource` throws `InstanceNotFoundException` on an empty result or a blank `keycloakUserId`.

`HouseRepositoryTest` passes when a saved house loads with the same `id`, `label`, `autonomyUsername`, and `keycloakUserId`, and when `toString` renders `id`, `label`, and `autonomyUsername`.

At that point the four stages match the handoffs in document 1.
