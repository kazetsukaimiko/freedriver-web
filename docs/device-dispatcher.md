# Device dispatcher

Home PCs run a headless Grok session when a dispatcher (the Grok app, the car, or the CLI) submits a job. This service is that in-memory queue. Keycloak issues the tokens. The device daemon on each PC starts Grok.

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. `quarkus.oidc.enabled` stays false until that client setup is applied. Anonymous callers receive 401. A device token calls the device routes. A dispatcher token calls the dispatcher routes.

The queue lives in memory for the life of the process. Postgres is the persistence step once bearer auth is on.

## Actors

| Actor | Role | Routes |
|---|---|---|
| Dispatcher | `dispatcher` | `GET /api/dispatcher/devices`, `POST /api/dispatcher/jobs`, `GET /api/dispatcher/jobs/{jobId}` |
| Device | `device` | `POST /api/device/register`, `POST /api/device/heartbeat`, `GET /api/device/assignments`, `POST /api/device/assignments/{id}/claim`, `POST /api/device/assignments/{id}` |

The device id is the token subject. When the access token carries `device_id`, that claim wins. A dispatcher only reads jobs it submitted.

`POST /api/dispatcher/jobs` body:

```json
{"deviceIds":["pc-luna"],"agentName":"yuni","prompt":"say hello","channel":"TEXT"}
```

`channel` is `TEXT` or `SPEECH`. One assignment is queued per device. `agentName` must be one that device registered, or the call fails immediately and nothing is queued. The call stays open until every assignment is `succeeded`, `failed`, or `expired`, or 75 seconds pass. At that deadline, non-terminal assignments for the job move to `expired`, and a later claim is rejected. A second submit for the same dispatcher subject is rejected while one submit is still in flight.

Assignment states: `queued` → `claimed` → `running` → `succeeded` | `failed`. `expired` is set at the submit deadline, and a device may also set it from any non-terminal state. `running` requires `grokSessionId`. A device only sees and updates its own assignments. `GET /api/device/assignments` long-polls for about 25 seconds.

## Deploy

Compose service `agent-dispatcher` has no host port. On `app.freedriver.io`, Caddy sends `/mcp`, `/mcp/*`, `/api/dispatcher`, `/api/dispatcher/*`, `/api/device`, and `/api/device/*` to `agent-dispatcher:8080`. Every other path stays on `dashboard:8080`.

`/mcp` is Streamable HTTP MCP for the `dispatcher` role. The tools are `list_devices`, `submit_job`, and `get_job`. They call this same queue. Legacy HTTP+SSE under `/mcp/` is not served. A session id is bound to the bearer token subject and is not itself a credential. Anonymous callers receive 401. A token without the dispatcher role receives 403. `McpApi` in `agent-dispatcher-api` is the `/mcp` contract, beside the REST interfaces a RestEasy proxy client can call.

The device daemon runs on the PC, outside Compose. It will register, heartbeat, poll, claim, and run `grok` with an explicit tool allowlist.

## Keycloak

`scripts/provision-keycloak-freedriver.sh` can create the public client `freedriver-dispatcher` (authorization code + PKCE, claim `actor=dispatcher`, realm role `dispatcher`). Device clients are created only when `FREEDRIVER_DEVICE_CLIENTS` lists their ids. Secrets go to `/opt/freedriver-secrets/` and stay out of the script output. Run that script on production as its own ops step.

Dashboard sign-in is the OIDC BFF against that same realm. Dispatcher and device clients get their tokens from Keycloak.
