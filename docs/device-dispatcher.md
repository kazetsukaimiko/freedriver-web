# Device dispatcher

Home PCs run a headless Grok session when a dispatcher (the Grok app, the car, or the CLI) submits a job. This service is that in-memory queue. Keycloak issues the tokens. The device daemon on each PC starts Grok. That daemon is the leftover headless path. OpenBot does not use it. OpenBot connects to the same MCP server as grok.com, with client `freedriver-openbot` and role `device`.

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. `%prod.quarkus.oidc.enabled` is true. The unprofiled property and the `%dev` and `%test` lines stay false. Anonymous callers receive 401, except the metadata GET below. A dispatcher token calls the dispatcher routes and must have `azp=freedriver-dispatcher`. A host token calls the host MCP tools and must have `azp=freedriver-openbot`. The service does not introspect tokens. `/api/device` is not served.

The queue lives in memory for the life of the process. Postgres is the persistence step once bearer auth is on.

## Actors

| Actor | Role | Routes |
|---|---|---|
| Dispatcher | `dispatcher` | `GET /api/dispatcher/devices`, `POST /api/dispatcher/jobs`, `GET /api/dispatcher/jobs/{jobId}` |
| Host | `device` | MCP tools `report_host` and `finish_job` on `/dispatch/mcp` |

The device id is the token subject. When the access token carries `device_id`, that claim wins. A dispatcher only reads jobs it submitted.

`POST /api/dispatcher/jobs` body:

```json
{"deviceIds":["pc-luna"],"agentName":"yuni","prompt":"say hello","channel":"TEXT"}
```

`channel` is `TEXT` or `SPEECH`. One assignment is queued per device. `agentName` must be one that device registered, or the call fails immediately and nothing is queued. The call stays open until every assignment is `succeeded`, `failed`, or `expired`, or 75 seconds pass. At that deadline, non-terminal assignments for the job move to `expired`, and a later claim is rejected. A second submit for the same dispatcher subject is rejected while one submit is still in flight.

Assignment states: `queued` → `claimed` → `running` → `succeeded` | `failed`. `expired` is set at the submit deadline, and a device may also set it from any non-terminal state. `running` records `grokSessionId` when the device sends one. The Grok daemon sends a session id. OpenBot posts the result with the MCP tool `finish_job`. `failed` is also allowed from `claimed` when the device refuses the assignment before work starts. `succeeded` is only from `running`. A device only sees and updates its own assignments. `AssignmentView` carries `channel` and `prompt` for the owning device's assignment. A read by any other device is forbidden. There is no `/api/device` long-poll.

## Deploy

Compose service `agent-dispatcher` has no host port. On `app.freedriver.io`, Caddy sends `/mcp`, `/mcp/*`, `/dispatch/mcp`, `/dispatch/mcp/*`, `/api/dispatcher`, and `/api/dispatcher/*` to `agent-dispatcher:8080`. `/autonomy/mcp` and `/api/autonomy` go to `autonomy-mcp:8080`. Every other path stays on `dashboard:8080`.

`/dispatch/mcp` is Streamable HTTP MCP. `/mcp` is the same contract. Role `dispatcher` sees `list_devices`, `submit_job`, and `get_job`. Role `device` sees `report_host` and `finish_job`. They call this same queue. Legacy HTTP+SSE under `/mcp/` is not served. A session id is bound to the bearer token subject and is not itself a credential. Anonymous `GET` and `POST` on `/dispatch/mcp` and `/mcp` receive 401 with `WWW-Authenticate: Bearer` and `resource_metadata="https://app.freedriver.io/api/dispatcher/oauth-protected-resource"`, and they do not run a tool. A token whose client id and role do not pair receives 403. `McpApi` in `agent-dispatcher-api` is the `/mcp` and `/dispatch/mcp` contract, beside the REST interfaces a RestEasy proxy client can call.

`GET /api/dispatcher/oauth-protected-resource` is the only unauthenticated dispatcher response. It is OAuth protected-resource metadata and nothing else: `resource` is `https://app.freedriver.io/dispatch/mcp`, `authorization_servers` is only `https://auth.freedriver.io/realms/freedriver`, and `bearer_methods_supported` is `header`. The document has no client secret and no devices, jobs, or prompts. `/dispatch/mcp`, `/mcp`, and other dispatcher routes stay authenticated.

The device daemon in `java/device-daemon/` runs on the PC. It registers, heartbeats, long-polls, claims, and starts one headless `grok` process per winning text claim. The tool allowlist is local configuration and defaults to no tools. The prompt is written to a mode `0600` file and passed with `--prompt-file`. That program still contains calls to `https://app.freedriver.io/api/device/*`. This service no longer serves those routes, so that daemon cannot register or poll. Its code is unchanged. A user systemd unit in the module is what #213 installs on the PC. The local session timeout kills that child process. The 75 second queue deadline stays in `JobStore`.

OpenBot polls `/dispatch/mcp` with client `freedriver-openbot`. `report_host` publishes the bots and whether each is idle or busy, and the reply is the queued jobs. An OpenBot routine routes `agentName` Project Manager to the project-manager agent, then `finish_job` posts the result. grok.com keeps client `freedriver-dispatcher` and the tools `list_devices`, `submit_job`, and `get_job`. The headless daemon above is not this poller.

## Keycloak

`scripts/provision-keycloak-freedriver.sh` can create the public client `freedriver-dispatcher` (authorization code + PKCE, claim `actor=dispatcher`, realm role `dispatcher`). Device clients are created only when `FREEDRIVER_DEVICE_CLIENTS` lists their ids. Secrets go to `/opt/freedriver-secrets/` and stay out of the script output. Run that script on production as its own ops step.

Dashboard sign-in is the OIDC BFF against that same realm. Dispatcher and device clients get their tokens from Keycloak.
