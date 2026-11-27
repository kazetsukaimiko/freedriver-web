# iPhone and Watch sign-in

Decided sign-in design from epic [#828](https://github.com/kazetsukaimiko/freedriver-web/issues/828) (iPhone) and epic [#829](https://github.com/kazetsukaimiko/freedriver-web/issues/829) (Watch), as those epics stand. Open points stay on the epics.

## One user-bearer tenant

From [#833](https://github.com/kazetsukaimiko/freedriver-web/issues/833) on, one user-bearer tenant admits a bearer when the issuer is the freedriver realm, the audience is `freedriver-api`, and the route allows the signed-in user's own roles. The Keycloak app registration's identity grants the caller nothing by itself.

Until that card, iPhone 0 keeps an app tenant that covers `GET /api/me` only. The internal tenant stays in place for autonomy-mcp.

## Code and PKCE

The iPhone app signs in through the Keycloak app registration (OAuth client `freedriver-ios`) with authorization code and PKCE, in the system browser session. The access token's audience is `freedriver-api`.

The Watch app signs in through the Keycloak app registration (OAuth client `freedriver-watchos`) with authorization code and PKCE S256 (RFC 8252), in `ASWebAuthenticationSession`. The redirect is `io.freedriver.watchos:/oauth2redirect`. The registration is public. The standard flow is on and PKCE S256 is required. The device grant, the implicit grant, the direct access grant and the service account stay off.

## Watch offline session

The watch requests `offline_access` in its authorization request. That scope is optional on `freedriver-watchos` and stays off the default scopes. The offline session for `freedriver-watchos` alone is capped at 30 days idle (`client.offline.session.idle.timeout` `2592000`) and 90 days maximum (`client.offline.session.max.lifespan` `7776000`). Other registrations keep the realm's own offline settings. The watch access token uses the realm's short default lifetime of 300 seconds. `freedriver-watchos` sets no access-token lifespan of its own.

The watch ends that session by revoking the refresh token on sign-out, and when the session is removed in Keycloak. Signing in again after an explicit sign-out on the watch requires fresh user authentication.

## GET /api/me

The signed-in screen shows the name the dashboard returns from the user's own token through `GET /api/me`.

## Open on the epics

Apple Developer Program membership, signing, the watch hardware, the HealthKit experiment and any later health upload stay open on [#828](https://github.com/kazetsukaimiko/freedriver-web/issues/828) and [#829](https://github.com/kazetsukaimiko/freedriver-web/issues/829).
