# Device dispatcher

Home PCs run a headless Grok session when a dispatcher (the Grok app, the car, or the CLI) submits a job. This service stores that queue in Mongo. Keycloak issues the tokens. The device daemon on each PC starts Grok. That daemon is the headless path in `java/device-daemon`. OpenBot connects to the same MCP server as grok.com, with client `freedriver-openbot` and role `device`.

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. `%prod.quarkus.oidc.enabled` is true. The unprofiled property and the `%dev` and `%test` lines stay false. Anonymous callers receive 401, except the metadata GET below. A dispatcher token calls the dispatcher routes and must have `azp=freedriver-dispatcher`. A host token calls the host MCP tools and must have `azp=freedriver-openbot`. Host and dispatcher calls use `/dispatch/mcp` and `/mcp`.

Jobs, the host roster, and the in-flight submit lock are rows in the dashboard Mongo database. Assignments are the `agent-jobs` collection. Hosts are `agent-devices`. A restart reads those rows. All job state, the device roster, and submit locks are read from the `agent-jobs`, `agent-devices`, and `agent-locks` collections on every operation. Each state change is one conditional Mongo write, so two dispatcher processes cannot both claim or finish an assignment, and two submits for one subject cannot both take the lock. A submit wait, a job wait, and a device long-poll re-read the rows every 250 ms, rising to 1 s, until the deadline.

## Actors

| Actor | Role | Routes |
|---|---|---|
| Dispatcher | `dispatcher` | `GET /api/dispatcher/devices`, `POST /api/dispatcher/jobs`, `GET /api/dispatcher/jobs/{jobId}` |
| Host | `device` | `POST /api/dispatcher/host/report`, `/take`, `/jobs/{assignmentId}/start`, `/jobs/{assignmentId}/finish`; MCP tools `report_host` and `finish_job` on `/dispatch/mcp` until #540 |

The device id is the token subject. When the access token carries `device_id`, that claim wins. A dispatcher only reads jobs it submitted.

`POST /api/dispatcher/jobs` body:

```json
{"deviceIds":["pc-luna"],"agentName":"yuni","prompt":"say hello","channel":"TEXT"}
```

`channel` is `TEXT` or `SPEECH`. One assignment is queued per device. `agentName` must be one that device registered, or the call fails immediately and nothing is queued. The call stays open until every assignment is `succeeded`, `failed`, or `expired`, or 75 seconds pass. At that deadline, non-terminal assignments for the job move to `expired`, and a later claim is rejected. When the assignment is queued, and `FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET` is set, the service POSTs a signed webhook to the OpenBot `dispatch-jobs` routine. The JSON body is the event type, the time, and the hostname. It does not include the prompt or the secret. An unset secret skips that POST, and the job still queues. The wait stays 75 seconds. A second submit for the same dispatcher subject is rejected while one submit is still in flight.

Assignment states: `queued` → `claimed` → `running` → `succeeded` | `failed`. `expired` is set at the submit deadline, and a device may also set it from any non-terminal state. `running` records `grokSessionId` when the device sends one. The Grok daemon sends a session id. OpenBot posts the result with the MCP tool `finish_job`. `failed` is also allowed from `claimed` when the device refuses the assignment before work starts. `succeeded` is only from `running`. A device only sees and updates its own assignments. `AssignmentView` carries `channel` and `prompt` for the owning device's assignment. A read by any other device is forbidden. The host reads queued work from `report_host`.

## Taking a job

A host program uses `HostApi` (`java/agent-dispatcher-api`), JSON in and out, role `device`. It publishes its roster, takes each job exactly once, says where it runs, and posts its result. Each call reads and moves only the caller's own assignments; the device id comes from the token. The paths are constants in `HostPaths` (`java/agent-dispatcher-model`), beside the request records, so the plain-Java connector uses the same ones.

| Route under `/api/dispatcher/host` | Body | Result |
| --- | --- | --- |
| `POST /report` | `HostReport`: `hostname`, `bots` (1 to 32 `ReportedBot`) | `DeviceView` |
| `POST /take` | `TakeRequest`: `waitSeconds` 0 to 50; `holding`, at most 64 assignment ids, optional | list of `AssignmentView` |
| `POST /jobs/{assignmentId}/start` | `StartRequest`: `deliveryRef`, 1 to 128 printable ASCII characters, no space | `AssignmentView` |
| `POST /jobs/{assignmentId}/finish` | `FinishRequest`: `succeeded`; `note`, at most 2,000 characters, optional | `AssignmentView` |

Refusals: 400 for a bad body, 403 for another device's assignment, 404 for an unknown one, 409 for a state the call does not start from. Anonymous is 401, and a token whose client id and role do not pair is 403.

`take`, for the caller's device, in this order:

1. Every open assignment named in `holding` gets `heldUntil = now + host-lease`.
2. Every `QUEUED` assignment older than `take-within` becomes `EXPIRED` with note `no host took it`.
3. Every other `QUEUED` assignment becomes `CLAIMED` with `heldUntil = now + host-lease`, and every `CLAIMED` or `RUNNING` assignment outside `holding` whose `heldUntil` is past or absent gets a new `heldUntil`. Each is one conditional `findOneAndUpdate`, and the reply holds only the rows a write matched, so two replicas or two calls hand each job to one caller.
4. When nothing was returned, steps 2 and 3 repeat on the store's re-read schedule (250 ms, doubling to 1 s) until `waitSeconds` have passed.

`start` moves `CLAIMED` to `RUNNING`, stores `deliveryRef`, and renews `heldUntil`. The same call again on a `RUNNING` assignment with the same `deliveryRef` returns it unchanged. Another `deliveryRef` is a conflict, and another device's assignment is refused. `AssignmentView.deliveryRef` is null until `start` sets it. `report` and `finish` are the store's `report` and `finish`.

Lease and expiry: `freedriver.dispatcher.host-lease` is 90 seconds and `freedriver.dispatcher.take-within` is 10 minutes. Start-up fails when `take-within` is under one second, and when `host-lease` is 50 seconds or less, the longest `take` wait, so a host that names its jobs in `holding` on every take keeps them. A job whose host stops taking is handed out again once its lease has passed, with its state and `deliveryRef`. The dispatcher logs one INFO line per returned job and per `start`: assignment id, device id, state, and `ageMs` since the job was stored.

## Deploy

Compose service `agent-dispatcher` has no host port. On `app.freedriver.io`, Caddy sends `/mcp`, `/mcp/*`, `/dispatch/mcp`, `/dispatch/mcp/*`, `/api/dispatcher`, and `/api/dispatcher/*` to `agent-dispatcher:8080`. `/autonomy/mcp` and `/api/autonomy` go to `autonomy-mcp:8080`. Every other path stays on `dashboard:8080`.

`/dispatch/mcp` is the Quarkus MCP server. `/mcp` is the same tools. Role `dispatcher` sees `list_agents`, `openbot_send_prompt`, `openbot_await_turn`, and `get_job`. Role `device` sees `report_host` and `finish_job`. They call this same queue. The server's SSE paths `/mcp/sse` and `/dispatch/mcp/sse` use the same bearer check. A session id is bound to the bearer token subject and is not itself a credential. That binding is the Redis key `freedriver:mcp:dispatcher:` plus the session id, and the key expires 24 hours after it is stored, so another dispatcher process accepts the same id. Anonymous `GET` and `POST` on `/dispatch/mcp` and `/mcp` receive 401 with `WWW-Authenticate: Bearer` and `resource_metadata="https://app.freedriver.io/api/dispatcher/oauth-protected-resource"`, and they do not run a tool. A token whose client id and role do not pair receives 403. The constraints on each tool argument are the validation and the advertised input schema; a bad argument is JSON-RPC error -32602 and an argument the tool does not declare is ignored. A call without the tool's role is JSON-RPC error -32005. The metadata document, the bearer challenge, and the session binding are `java/mcp-support`, shared with the autonomy MCP server. The same sticky-routing note as the autonomy server applies: a second replica needs Caddy `lb_policy header Mcp-Session-Id`.

`GET /api/dispatcher/oauth-protected-resource` is the only unauthenticated dispatcher response. It is OAuth protected-resource metadata and nothing else: `resource` is `https://app.freedriver.io/dispatch/mcp`, `authorization_servers` is only `https://auth.freedriver.io/realms/freedriver`, and `bearer_methods_supported` is `header`. The document has no client secret and no devices, jobs, or prompts. `/dispatch/mcp`, `/mcp`, and other dispatcher routes stay authenticated.

The device daemon in `java/device-daemon/` runs on the PC. It registers, heartbeats, long-polls, claims, and starts one headless `grok` process per winning text claim. The tool allowlist is local configuration and defaults to no tools. The prompt is written to a mode `0600` file and passed with `--prompt-file`. That program still contains calls to `https://app.freedriver.io/api/device/*`. This service answers the host on `/dispatch/mcp` with `report_host` and `finish_job`. A request from that program to those URLs receives 404. This pull request leaves the daemon source as it is on main. A user systemd unit in the module is what #213 installs on the PC. The local session timeout kills that child process. The 75 second queue deadline stays in `JobStoreService`, the Mongo implementation of `JobStore`.

A queued job notifies the OpenBot routine `dispatch-jobs`. That routine calls `/dispatch/mcp` with client `freedriver-openbot`. `report_host` publishes each bot's name, whether it is idle or busy, and a short activity note, and the reply is this host's jobs whose status is `QUEUED`. The routine routes `agentName` to the named agent, then `finish_job` posts the result. grok.com keeps client `freedriver-dispatcher` and the tools `list_agents`, `openbot_send_prompt`, `openbot_await_turn`, and `get_job`. `openbot_send_prompt` writes the job to Mongo and returns the job id. It does not wait for OpenBot. Another thread POSTs the `dispatch-jobs` webhook. Optional `on_busy` is `supersede` or `queue`. Omit it, or pass null, and the stored value is `supersede`. Anything else is rejected. The value is stored on the `agent-jobs` row before that webhook runs, and `report_host` returns it with each job whose status is `QUEUED`. `GET /api/jobs` returns it as `on_busy` on each row. The webhook itself does not change. `openbot_await_turn` takes that job id and `delaySeconds`, at most 120. It returns when the job is done, or when that delay elapses, and a timeout does not expire the job. More than 120 seconds is rejected. `get_job` reads the job without waiting. The REST call above still waits 75 seconds. `GET /api/jobs` lists every caller's assignments, newest first, for a portal-admin session, the same session as `/api/phones`. The dashboard reads them from the dispatcher's `GET /api/dispatcher/admin/jobs` as the service account `freedriver-portal-jobs`, whose token carries realm role `dispatcher-admin`; the dispatcher accepts that client only with that role. That path is on the compose network only; Caddy answers 404 for `/api/dispatcher/admin` from outside. Each row is an `AssignmentView`. The Agent jobs page is `/agent-jobs`.

## Keycloak

`scripts/provision-keycloak-freedriver.sh` can create the public client `freedriver-dispatcher` (authorization code + PKCE, claim `actor=dispatcher`, realm role `dispatcher`). That public client is the Grok connector. Anonymous client registration (DCR) Trusted Hosts are `grok.com`, `console.x.ai`, `x.ai`, and `*.x.ai`, so Grok Connect Apps can add `/dispatch/mcp` and `/autonomy/mcp` with a grok.com redirect. New clients use redirect URIs on those hosts. Keycloak matches those URIs. Grok's registration POST may come from xAI egress. Those DCR clients keep a limited role set. The roles client scope includes `mcp-user` and `dashboard`, so a grok.com DCR access token carries those roles when the signed-in user holds them. The OpenBot MCP client is the separate confidential client `freedriver-openbot`: authorization code + PKCE with redirect `http://127.0.0.1:28417/mcp-auth` for OpenBot Sign in, plus client credentials, realm role `device`, for `/dispatch/mcp`. `ensure_openbot_mcp_client` runs after `ensure_device_client` so other device ids stay client-credentials. Device clients for the leftover PC daemon are created when `FREEDRIVER_DEVICE_CLIENTS` lists their ids, or when that variable is unset and `/opt/freedriver-secrets/keycloak-device-clients.env` names them. Secrets go to `/opt/freedriver-secrets/` and stay out of the script output. Run that script on production as its own ops step.

Dashboard sign-in is the OIDC BFF against that same realm. Dispatcher and device clients get their tokens from Keycloak.
