# Autonomy MCP

Grok talks to house appliances over Streamable HTTP MCP at `https://app.freedriver.io/autonomy/mcp`. That URL is its own Quarkus process (`java/autonomy-mcp/`).

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. The access token audience is `https://app.freedriver.io/autonomy/mcp`. `azp` must be `freedriver-grok-mcp`. `%prod.quarkus.oidc.enabled` is true. The unprofiled property and the `%dev` and `%test` lines stay false. Anonymous callers receive 401 with `WWW-Authenticate: Bearer resource_metadata="https://app.freedriver.io/api/autonomy/oauth-protected-resource"` and no login redirect. The grok.com Custom connector uses public client `freedriver-grok-mcp` (authorization code + PKCE S256) with redirect `https://grok.com/connectors-oauth-exchange-code/`.

The process is off unless compose sets `FREEDRIVER_AUTONOMY_MCP_ENABLED=true`. Off answers 404 on `/autonomy/mcp`. Live-commands remain a dashboard setting.

## Tools

| Tool | Roles | Dashboard call |
|---|---|---|
| `list_appliances` | `mcp-user` | `GET /api/mcp/appliances` |
| `home_status` | `mcp-user` | `GET /api/mcp/home-status` |
| `debug` | `mcp-user` and `mcp-debug` | `GET /api/mcp/debug` |
| `switch_appliance` | `mcp-user` and (`dashboard` or `portal-admin`) | `POST /api/appliances/{id}/{name}` |

Every tool requires `mcp-user`. Switch also requires `dashboard` or `portal-admin`. After the bearer check, the process calls `http://dashboard:8080` with `X-Freedriver-Subject`, `X-Freedriver-Roles`, and `X-Freedriver-Internal`. Caddy strips those three headers on the public dashboard proxy. Switch uses the same command path, live-commands gate, command limiter, and appliance audit subject as the portal.

Rate limit is 30 calls per 60 seconds per subject per tool. Switch also takes the dashboard command limiter. Spoken replies name appliances and omit instance ids.

`GET /api/autonomy/oauth-protected-resource` is the only unauthenticated autonomy response. It is OAuth protected-resource metadata. `/autonomy/mcp` requires a bearer. A session id names the bearer subject that opened it. That binding is the Redis key `freedriver:mcp:autonomy:` plus the session id, and the key expires 24 hours after it is stored, so another autonomy-mcp process accepts the same id.

## Deploy

Compose service `autonomy-mcp` has no host port. On `app.freedriver.io`, Caddy sends `/autonomy/mcp`, `/autonomy/mcp/*`, `/api/autonomy`, and `/api/autonomy/*` to `autonomy-mcp:8080`.
