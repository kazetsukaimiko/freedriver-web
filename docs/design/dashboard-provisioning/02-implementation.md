# Implementation

How document 1 is built. Names below are the names in that document.

`InstanceView` in `java/dashboard-model` stays the appliance snapshot. `ProvisionedInstance` is the new view.

## Existing code

### Instances page

`java/dashboard/src/main/webui/src/App.tsx` builds the primary nav. `primaryNavItems` adds Phone sign-in when `portalAdmin` is true, and that flag is the `GET /api/invites` result. The change adds an Instances item beside Phone sign-in, path `/instances`, under the same flag, and renders the Instances page for that path.

`java/dashboard/src/main/webui/src/App.test.tsx` covers that menu. The change expects the Instances link for a portal-admin and expects it to be absent otherwise.

New files beside `phones.ts`, `PhoneSignIn.tsx`, and `PhoneSignIn.test.tsx`:

| Path | Role |
| --- | --- |
| `java/dashboard/src/main/webui/src/instances.ts` | `INSTANCES_PATH`, the request and response types, and the fixed failure sentences. |
| `java/dashboard/src/main/webui/src/Instances.tsx` | The create form, the list, the opened house, and the copy control. |
| `java/dashboard/src/main/webui/src/Instances.test.tsx` | The page tests. |
| `java/dashboard/src/main/webui/src/instances.css` | Layout for the list and the secret row. |

The page calls `apiFetch` the same way `phones.ts` does. Create sends `X-CSRF-Token`.

### House provision

`InviteResource` in `java/dashboard/src/main/java/io/freedriver/app/invites/InviteResource.java` is the portal-admin resource beside this flow. The new types live in `io.freedriver.app.instances`, next to that package.

Paths under `java/dashboard/src/main/java/io/freedriver/app/instances/`:

| Path | Role |
| --- | --- |
| `InstancesApi.java` | JAX-RS interface. Path `/api/instances`. `@RolesAllowed("portal-admin")` on each method. `@Valid` and `@Size(max = ApplianceSchemas.NAME_MAX)` on `CreateInstance.label`. `@RateLimited("instances")` on create. |
| `InstancesResource.java` | `@ApplicationScoped` implementation of that interface. Returns `InstanceList` and `ProvisionedInstance`. Throws the domain exceptions below. |
| `HouseProvision.java` | Plain service. Mints the id and the secret, calls broker allow, then the store. Returns `House` or throws a domain exception. |
| `InvalidLabelException.java` | Label failed the contract. |
| `BrokerRefusedException.java` | The allow or the following insert failed. |
| `InstanceNotFoundException.java` | Open's lookup was empty. |
| `InstanceFailureMapper.java` | Maps those three exceptions to `InstanceFailure` and the HTTP status in document 1. |

`InstancesApi` carries the JAX-RS, validation, role, and rate-limit annotations. `InstancesResource` is the application-scoped implementation. `HouseProvision` returns `House` or throws a domain exception. The role check lives on `InstancesApi`. The rate limit lives on the existing `RateLimitedFilter`, keyed by the authenticated subject, bucket `instances`.

`CsrfFilter` in `java/dashboard/src/main/java/io/freedriver/app/security/CsrfFilter.java` already requires `X-CSRF-Token` on `POST /api/invites` and writes `csrfToken` onto `InviteList`. The change treats `POST /api/instances` as a command path and writes `csrfToken` onto an `InstanceList` from `GET /api/instances`.

### Broker allow

`MqttLiveClient`, `MqttConnectionProducer`, and `MqttSettings` in `io.freedriver.app.appliances` are the api user's session. `MqttConnection` and `PahoMqttConnection` are the session types. `MqttBrokers` refuses a public host, plaintext 1883, and a topic that contains `+` or `#`. `ApplianceSchemas` formats the two exact topics.

`MqttSettings` gains the admin username, the admin password, the admin password file, and `api-role`. The password resolution matches the api password: the configured password wins, and the file is the fallback. Keys:

| Key | Where |
| --- | --- |
| `freedriver.mqtt.admin-username` | `application.properties`, from the environment, same pattern as the api username. |
| `freedriver.mqtt.admin-password` | Environment, same pattern as `FREEDRIVER_MQTT_API_PASSWORD`. |
| `freedriver.mqtt.admin-password-file` | `application-prod.properties`, path `/opt/freedriver-secrets/mosquitto/admin.pass`, beside the existing `api.pass` path. |
| `freedriver.mqtt.api-role` | `application.properties`, default `api`. |

New files:

| Path | Role |
| --- | --- |
| `java/dashboard/src/main/java/io/freedriver/app/instances/BrokerAllow.java` | Port `HouseProvision` calls. Methods accept and return `InstanceAllow`, `InstanceAllowResult`, and `InstanceAllowRollback`. |
| `java/dashboard/src/main/java/io/freedriver/app/appliances/MqttBrokerAllow.java` | That port on a short-lived `PahoMqttConnection` with the admin login, the trust certificate `MqttSettings` already uses, and `MqttBrokers.assertLiveBroker`. |

`MqttLiveClient` keeps reading ids from `freedriver.mqtt.instance-ids` and `instance-ids-file`.

The allow publish is one dynamic-security `commands` array on `$CONTROL/dynamic-security/v1`:

| Order | Command | What it binds |
| --- | --- | --- |
| 1 | `createRole` | `autonomyRole`, with the autonomy grants in the role's `acls`. |
| 2 | `createClient` | `autonomyUsername`, `brokerSecret`, and `autonomyRole`. |
| 3 | `addRoleACL` | The api grants on `apiRole`. |

Autonomy grants, exact topic, `allow` true: `publishClientSend` on `appliancesTopic`, `subscribeLiteral` on `commandsTopic`, `publishClientReceive` on `commandsTopic`.

Api grants, exact topic, `allow` true: `subscribeLiteral` on `appliancesTopic`, `publishClientReceive` on `appliancesTopic`, `publishClientSend` on `commandsTopic`.

Each command carries `correlationData` set to `instanceId`. QoS is `ApplianceSchemas.QOS`. Retain is `ApplianceSchemas.RETAIN`.

The client subscribes to `$CONTROL/dynamic-security/v1/response` and keeps the response whose `correlationData` is this `instanceId`. `allowed` is true when every response entry for that correlation is an acceptance. `partial` is true when an earlier command in the array was accepted and a later one failed, or when the publish returned and the response timed out. A failure before the publish leaves `partial` false.

`InstanceAllowRollback` is one `commands` array on the same topic: `deleteClient` for `autonomyUsername`, `deleteRole` for `autonomyRole`, and `removeRoleACL` for each api grant that was sent. The api user is left in place. One publish. The outcome is still `BrokerRefusedException` when this publish fails.

`MqttLog` gains an allow line whose arguments are `instanceId` and `allowed`.

### Instance store

`House` and `HouseRepository` already sit in `io.freedriver.app.instances`. `java/dashboard/pom.xml` already depends on `quarkus-mongodb-panache`. `docker-compose.yml` already runs `mongodb` from `mongo:8.0`, with data at `/opt/freedriver-storage/mongodb`, and the dashboard already takes `QUARKUS_MONGODB_CONNECTION_STRING`. `application.properties` already sets `quarkus.mongodb.database` to `freedriver`, leaves Dev Services off, and keeps `org.mongodb.driver` at WARN.

| Path | Now | Change |
| --- | --- | --- |
| `java/dashboard/src/main/java/io/freedriver/app/instances/House.java` | Panache entity for collection `instances`. Fields `id`, `label`, `autonomyUsername`, `brokerSecret`. `toString` omits `brokerSecret`. | House provision fills `autonomyUsername` with `id`. The fields stay these four. |
| `java/dashboard/src/main/java/io/freedriver/app/instances/HouseRepository.java` | `save` and `load`. `lowercaseUuid` requires the canonical lowercase form. | Gains `list`, returning every `House`, for the list handoff. |

`HouseRepositoryTest` already saves and loads one house and checks that `toString` omits the secret. The list method's assertion joins that class.

## How document 1 is built

### Stage 1, Instances page

`Instances.tsx` is the stage. `instances.ts` is the client for the three direct handoffs.

`CreateInstance` is the JSON body of `POST /api/instances`. The page sends the `csrfToken` from the latest `InstanceList` as `X-CSRF-Token`. When the response is `ProvisionedInstance`, the page shows `instanceId` and `brokerSecret`. When the response is `InstanceFailure`, the page shows the fixed sentence for `cause`.

`InstanceList` is the JSON body of `GET /api/instances`. Each `InstanceSummary` is one row. The row's open action sends `OpenInstance` as `GET /api/instances/{instanceId}`.

`ProvisionedInstance` from that GET is the opened house. The copy control writes `instanceId` and `brokerSecret` to the clipboard and leaves both visible in the row.

`App.tsx` shows the nav item when `portalAdmin` is true. A 403 from the list renders the denied state, the same shape as `PhoneSignIn`. `App.test.tsx` and `Instances.test.tsx` pass when a portal-admin reaches the heading Instances, the menu for any other role is Dashboard alone, create renders the secret from a `ProvisionedInstance` response, and a `cause` of `broker` renders the failure sentence while the list is unchanged.

### Stage 2, House provision

`InstancesResource` and `HouseProvision` are the stage. `InstancesApi` is the edge.

The create handoff reaches `HouseProvision` after `@RolesAllowed`, `CsrfFilter`, and `@RateLimited("instances")`. `HouseProvision` trims `label`, mints `id` and `brokerSecret`, and builds `InstanceAllow`. It calls `BrokerAllow`, then `HouseRepository.save` when `InstanceAllowResult.allowed` is true.

`InvalidLabelException`, `BrokerRefusedException`, and `InstanceNotFoundException` leave the resource as those types. `InstanceFailureMapper` writes `InstanceFailure`. The resource returns `ProvisionedInstance` on success, with `instanceId` and `autonomyUsername` taken from `House.id`.

`InstancesResourceTest` lives at `java/dashboard/src/test/java/io/freedriver/app/instances/InstancesResourceTest.java`. It passes when a `portal-admin` create with a stub allow and a stub store returns `ProvisionedInstance`, a refused allow leaves the stub store as it started, a label past 64 characters returns cause `label` before the stub broker is invoked, and any other role receives 403.

### Stage 3, Broker allow

`MqttBrokerAllow` is the stage. `BrokerAllow` is the port `HouseProvision` calls, so the mint and the store stay free of the MQTT client.

`MqttBrokerAllow` opens one admin session, publishes `InstanceAllow` as the command table above, and reads `InstanceAllowResult` from the response topic. It checks both topics with `MqttBrokers.assertExactTopic` before the publish. On `partial` true, `HouseProvision` calls the port with `InstanceAllowRollback` and then throws `BrokerRefusedException`.

`MqttBrokerAllowTest` lives at `java/dashboard/src/test/java/io/freedriver/app/appliances/MqttBrokerAllowTest.java`. It passes when the published topics are the two `ApplianceSchemas` paths for the minted id, the autonomy and api grants match document 1, those grants are `appliancesTopic` and `commandsTopic`, retain is false, a response error after `createClient` sets `partial` true, and the log line's text is `instanceId` plus the outcome.

### Stage 4, Instance store

`HouseRepository` and `House` are the stage. `HouseProvision` calls `save` after `allowed` is true, `list` for the list handoff, and `load` for open.

The insert is the document 1 handoff of `House`. A duplicate `id` or a Mongo failure throws, and `HouseProvision` treats that as `BrokerRefusedException` after rollback. List maps each `House` to an `InstanceSummary`. Open returns one house or an empty result, and `InstancesResource` throws `InstanceNotFoundException` on empty.

`HouseRepositoryTest` already passes when a saved house loads with the same `id`, `label`, `autonomyUsername`, and `brokerSecret`, and when `toString` omits the secret.

At that point the four stages match the handoffs in document 1.
