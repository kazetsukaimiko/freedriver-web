# Autonomy MCP

Grok talks to house appliances over Streamable HTTP MCP at `https://app.freedriver.io/autonomy/mcp`. That URL is its own Quarkus process (`java/autonomy-mcp/`), not the dashboard and not the dispatcher.

Keycloak at `https://auth.freedriver.io/realms/freedriver` is the issuer. The access token audience is `https://app.freedriver.io/autonomy/mcp`. `azp` must be `freedriver-grok-mcp`. `%prod.quarkus.oidc.enabled` is true. The unprofiled property and the `%dev` and `%test` lines stay false. Anonymous callers receive 401 with `WWW-Authenticate: Bearer resource_metadata="https://app.freedriver.io/api/autonomy/oauth-protected-resource"` and no login redirect.

The process is off unless compose sets `FREEDRIVER_AUTONOMY_MCP_ENABLED=true`. Off answers 404 on `/autonomy/mcp`. This card does not turn live-commands on.

## Tools

| Tool | Roles | Dashboard call |
|---|---|---|
| `list_appliances` | `mcp-user` | `GET /api/mcp/appliances` |
| `home_status` | `mcp-user` | `GET /api/mcp/home-status` |
| `debug` | `mcp-user` and `mcp-debug` | `GET /api/mcp/debug` |
| `switch_appliance` | `mcp-user` and (`dashboard` or `portal-admin`) | `POST /api/appliances/{id}/{name}` |

`portal-admin` alone is not enough. After the bearer check, the process calls `http://dashboard:8080` with `X-Freedriver-Subject`, `X-Freedriver-Roles`, and `X-Freedriver-Internal`. It does not copy the Grok `Authorization` header. Caddy strips those three headers on the public dashboard proxy. Switch uses the same command path, live-commands gate, command limiter, and appliance audit subject as the portal.

Rate limit is 30 calls per 60 seconds per subject per tool. Switch also takes the dashboard command limiter. Spoken replies do not include instance ids.

`GET /api/autonomy/oauth-protected-resource` is the only unauthenticated autonomy response. It is OAuth protected-resource metadata. `/autonomy/mcp` stays authenticated. `/appliances/mcp`, `/mcp`, and `/dispatch/mcp` are other services.

## Deploy

Compose service `autonomy-mcp` has no host port. On `app.freedriver.io`, Caddy sends `/autonomy/mcp`, `/autonomy/mcp/*`, `/api/autonomy`, and `/api/autonomy/*` to `autonomy-mcp:8080`.
