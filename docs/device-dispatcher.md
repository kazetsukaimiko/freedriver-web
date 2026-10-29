# Device dispatcher

Home PCs run a headless Grok session when a dispatcher (the Grok app, the car, or the CLI) submits a job. This service is that in-memory queue. Keycloak issues the tokens. The device daemon on each PC starts Grok.

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. `%prod.quarkus.oidc.enabled` is true. The unprofiled property and the `%dev` and `%test` lines stay false. Anonymous callers receive 401, except the metadata GET below. A device token calls the device routes. A dispatcher token calls the dispatcher routes. Dispatcher calls require `azp=freedriver-dispatcher`. The service does not introspect tokens.

The queue lives in memory for the life of the process. Postgres is the persistence step once bearer auth is on.

## Actors

| Actor | Role | Routes |
|---|---|---|
| Dispatcher | `dispatcher` | `GET /api/dispatcher/devices`, `POST /api/dispatcher/jobs`, `GET /api/dispatcher/jobs/{jobId}` |
| Device | `device` | `POST /api/device/register`, `POST /api/device/heartbeat`, `GET /api/device/assignments`, `GET /api/device/assignments/{id}`, `POST /api/device/assignments/{id}/claim`, `POST /api/device/assignments/{id}` |

The device id is the token subject. When the access token carries `device_id`, that claim wins. A dispatcher only reads jobs it submitted.

`POST /api/dispatcher/jobs` body:

```json
{"deviceIds":["pc-luna"],"agentName":"yuni","prompt":"say hello","channel":"TEXT"}
```

`channel` is `TEXT` or `SPEECH`. One assignment is queued per device. `agentName` must be one that device registered, or the call fails immediately and nothing is queued. The call stays open until every assignment is `succeeded`, `failed`, or `expired`, or 75 seconds pass. At that deadline, non-terminal assignments for the job move to `expired`, and a later claim is rejected. A second submit for the same dispatcher subject is rejected while one submit is still in flight.

Assignment states: `queued` → `claimed` → `running` → `succeeded` | `failed`. `expired` is set at the submit deadline, and a device may also set it from any non-terminal state. `running` requires `grokSessionId`. `failed` is also allowed from `claimed` when the device refuses the assignment before a session starts. `succeeded` is only from `running`. A device only sees and updates its own assignments. `AssignmentView` carries `channel` and `prompt` for the owning device's assignment. A read by any other device is forbidden. `GET /api/device/assignments` long-polls for about 25 seconds.

## Deploy

Compose service `agent-dispatcher` has no host port. On `app.freedriver.io`, Caddy sends `/mcp`, `/mcp/*`, `/dispatch/mcp`, `/dispatch/mcp/*`, `/api/dispatcher`, `/api/dispatcher/*`, `/api/device`, and `/api/device/*` to `agent-dispatcher:8080`. Every other path stays on `dashboard:8080`.

`/dispatch/mcp` is Streamable HTTP MCP for the `dispatcher` role. `/mcp` is the same contract. The tools are `list_devices`, `submit_job`, and `get_job`. They call this same queue. Legacy HTTP+SSE under `/mcp/` is not served. A session id is bound to the bearer token subject and is not itself a credential. Anonymous `GET` and `POST` on `/dispatch/mcp` and `/mcp` receive 401 with `WWW-Authenticate: Bearer` and `resource_metadata="https://app.freedriver.io/api/dispatcher/oauth-protected-resource"`, and they do not run a tool. A token without the dispatcher role receives 403. `McpApi` in `agent-dispatcher-api` is the `/mcp` and `/dispatch/mcp` contract, beside the REST interfaces a RestEasy proxy client can call.

`GET /api/dispatcher/oauth-protected-resource` is the only unauthenticated dispatcher response. It is OAuth protected-resource metadata and nothing else: `resource` is `https://app.freedriver.io/dispatch/mcp`, `authorization_servers` is only `https://auth.freedriver.io/realms/freedriver`, and `bearer_methods_supported` is `header`. The document has no client secret and no devices, jobs, or prompts. `/dispatch/mcp`, `/mcp`, and other dispatcher routes stay authenticated.

The device daemon in `java/device-daemon/` runs on the PC. It registers, heartbeats, long-polls, claims, and starts one headless `grok` process per winning text claim. The tool allowlist is local configuration and defaults to no tools. The prompt is written to a mode `0600` file and passed with `--prompt-file`. The client calls `https://app.freedriver.io/api/device/*` with TLS verification on, using its device client credentials. A user systemd unit in the module is what #213 installs on the PC. The local session timeout kills that child process. The 75 second queue deadline stays in `JobStore`.

## Keycloak

`scripts/provision-keycloak-freedriver.sh` can create the public client `freedriver-dispatcher` (authorization code + PKCE, claim `actor=dispatcher`, realm role `dispatcher`). Device clients are created only when `FREEDRIVER_DEVICE_CLIENTS` lists their ids. Secrets go to `/opt/freedriver-secrets/` and stay out of the script output. Run that script on production as its own ops step.

Dashboard sign-in is the OIDC BFF against that same realm. Dispatcher and device clients get their tokens from Keycloak.
