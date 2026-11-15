# Autonomy MCP

Grok talks to house appliances over Streamable HTTP MCP at `https://app.freedriver.io/autonomy/mcp`. That URL is its own Quarkus process (`java/autonomy-mcp/`).

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. The access token audience is `https://app.freedriver.io/autonomy/mcp`. Mapper `audience-autonomy-mcp` on `freedriver-grok-mcp` and grok.com Dynamic Client Registration clients puts that audience on the access token. Client scope `autonomy-mcp` is a realm default so a later grok.com registration gets it too. `azp` is the Keycloak client id: `freedriver-grok-mcp` for the public client, or the client id Keycloak assigned when grok.com registered through Dynamic Client Registration. `%prod.quarkus.oidc.enabled` is true. The unprofiled property and the `%dev` and `%test` lines stay false. Anonymous callers receive 401 with `WWW-Authenticate: Bearer resource_metadata="https://app.freedriver.io/api/autonomy/oauth-protected-resource"` and no login redirect. The grok.com Custom connector uses public client `freedriver-grok-mcp` (authorization code + PKCE S256) with redirect `https://grok.com/connectors-oauth-exchange-code/`.

The process is off unless compose sets `FREEDRIVER_AUTONOMY_MCP_ENABLED=true`. Off answers 404 on `/autonomy/mcp`. Live-commands remain a dashboard setting.

## Tools

| Tool | Roles | Dashboard call |
|---|---|---|
| `list_appliances` | `mcp-user` | `GET /api/mcp/appliances` |
| `home_status` | `mcp-user` | `GET /api/mcp/home-status` |
| `debug` | `mcp-user` and `mcp-debug` | `GET /api/mcp/debug` |
| `switch_appliance` | `mcp-user` and `dashboard` | `POST /api/appliances/{id}/{name}` now, or after `delaySeconds` |

The tools are `AutonomyTools` on the Quarkus MCP server (`quarkus-mcp-server-http`, root path `/autonomy/mcp`). A caller without `mcp-user` receives 403 on `/autonomy/mcp` before any tool. Switch also requires `dashboard`, and `debug` requires `mcp-debug`; a call without the tool's role is JSON-RPC error -32005. The constraints on each tool argument are the validation and the advertised input schema. A bad argument, including a fraction, a quoted number, or a quoted boolean, is JSON-RPC error -32602 that names the argument or the constraint. An argument the tool does not declare is ignored. The grok.com sign-in user is `kaze`; that account holds `mcp-user` and `dashboard`. After the bearer check, the process calls `http://dashboard:8080` as the connecting user. It exchanges the caller's access token at Keycloak (RFC 8693 token exchange) as the confidential client `freedriver-autonomy-mcp`, for a token with audience `freedriver-api`, the same `sub`, and only those of `mcp-user` and `dashboard` the caller holds. That token is the bearer on the dashboard call. The dashboard checks it in its `internal` OIDC tenant, which takes a bearer only on `/api/mcp` and `/api/appliances`, requires audience `freedriver-api` and `azp` `freedriver-autonomy-mcp`, and its own `@RolesAllowed` decides. No secret is shared between the two services. The exchanged token carries no `portal-admin` or `mcp-debug`: `debug` checks `mcp-debug` on the caller's own token, and the dashboard's debug route asks for `mcp-user`. When Keycloak refuses the exchange, the tool answers "That was refused." and no dashboard route is called. Switch uses the same command path, live-commands gate, command limiter, and appliance audit subject as the portal.

`switch_appliance` takes optional `delaySeconds`, an integer from 0 through 86400. Five minutes is 300. Omit it, or pass 0, and the call switches now and waits for the dashboard result. A positive delay posts the switch to the dashboard (`POST /api/mcp/scheduled-switches`) and the call returns when the dashboard has stored it. The reply names the appliance and the wait, for example "Fridge will turn off in 5 minutes." To leave the fridge light on for five minutes, switch it on with no delay, then switch it off with `delaySeconds` 300. The dashboard stores the row in the Mongo collection `scheduled-switches` and fires it when it is due through the same command path, audited as the user who scheduled it. One dashboard replica claims each due row. A confirmed row is deleted; a failed or timed-out command is tried again, up to three attempts.

Rate limit is 30 calls per 60 seconds per subject per tool. Switch also takes the dashboard command limiter. Spoken replies name appliances and omit instance ids.

`GET /api/autonomy/oauth-protected-resource` is the only unauthenticated autonomy response. It is OAuth protected-resource metadata. `/autonomy/mcp` requires a bearer. A session id names the bearer subject that opened it, and a different subject presenting it receives 403. That binding is the Redis key `freedriver:mcp:autonomy:` plus the session id, and the key expires 24 hours after it is stored, so another autonomy-mcp process accepts the same id.

The metadata document, the bearer challenge, the metadata permit policy, the audit line, and the session-to-subject binding are `java/mcp-support`, the library both MCP servers use. Each service sets `freedriver.mcp.resource`, `freedriver.mcp.authorization-servers`, `freedriver.mcp.metadata-path`, `freedriver.mcp.paths`, `freedriver.mcp.roles`, and `freedriver.mcp.session-prefix`.

## Deploy

Compose service `autonomy-mcp` has no host port. On `app.freedriver.io`, Caddy sends `/autonomy/mcp`, `/autonomy/mcp/*`, `/api/autonomy`, and `/api/autonomy/*` to `autonomy-mcp:8080`.

Compose loads `/opt/freedriver-secrets/keycloak-freedriver-autonomy-mcp.env` into `autonomy-mcp`. `scripts/provision-keycloak-freedriver.sh` creates the client `freedriver-autonomy-mcp` and writes `FREEDRIVER_AUTONOMY_MCP_CLIENT_SECRET` to that file once. Run the script on the live realm before the release that carries the token exchange. Until the client and the file exist, every tool that calls the dashboard is refused.

A Streamable HTTP session keeps state in the process that opened it, so a second replica needs sticky routing on the session header: `lb_policy header Mcp-Session-Id` in the Caddy `reverse_proxy` block. TechOps adds that line when a second replica exists.
