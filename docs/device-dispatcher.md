# Device dispatcher

Home PCs run a headless Grok session when a dispatcher (the Grok app, the car, or the CLI) submits a job. This service is the queue. It does not mint tokens and it does not start Grok.

Keycloak at `https://auth.freedriver.io/realms/freedriver` stays the only issuer. `quarkus.oidc.enabled` is false until that client setup is applied. Anonymous callers get 401. A device token cannot call dispatcher routes, and a dispatcher token cannot call device routes.

The queue is in memory. A restart drops it. Postgres waits until bearer auth is on.

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

`channel` is `TEXT` or `SPEECH`. One assignment is queued per device. `agentName` must be one that device registered, or the call fails immediately and nothing is queued. The call stays open until every assignment is `succeeded`, `failed`, or `expired`, or 75 seconds pass. If nobody claims, the assignment is still `queued`.

Assignment states: `queued` → `claimed` → `running` → `succeeded` | `failed`. `expired` is allowed from any non-terminal state. `running` requires `grokSessionId`. A device only sees and updates its own assignments. `GET /api/device/assignments` long-polls for about 25 seconds.

## Deploy

Compose service `agent-dispatcher` has no host port. On `app.freedriver.io`, Caddy sends `/mcp`, `/mcp/*`, `/api/dispatcher`, `/api/dispatcher/*`, `/api/device`, and `/api/device/*` to `agent-dispatcher:8080`. Every other path stays on `dashboard:8080`.

`/mcp` is routed and still requires a caller. The Streamable HTTP MCP tools are not in this service yet. The JAX-RS interfaces in `agent-dispatcher-api` are the contract a RestEasy proxy client can call.

The device daemon is not a Compose service. It will register, heartbeat, poll, claim, and run `grok` on the PC. No `--yolo`.

## Keycloak

`scripts/provision-keycloak-freedriver.sh` can create the public client `freedriver-dispatcher` (authorization code + PKCE, claim `actor=dispatcher`, realm role `dispatcher`). Device clients are created only when `FREEDRIVER_DEVICE_CLIENTS` lists their ids. Secrets go to `/opt/freedriver-secrets/` and are not printed. Do not run the script against production as part of shipping this change.

Token issuance on the dashboard stays unimplemented while dashboard OIDC is off.
