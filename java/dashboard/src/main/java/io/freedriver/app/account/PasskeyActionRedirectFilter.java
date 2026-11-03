package io.freedriver.app.account;

import io.quarkus.oidc.OidcRedirectFilter;
import io.quarkus.oidc.OidcTenantConfig;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * When the account page has armed a passkey cookie, the next authorization
 * redirect to Keycloak includes {@code kc_action=webauthn-register-passwordless}.
 * WebAuthn runs on {@code auth.freedriver.io}, not the dashboard origin.
 */
@ApplicationScoped
public class PasskeyActionRedirectFilter implements OidcRedirectFilter {

    @Override
    public void filter(OidcRedirectContext context) {
        RoutingContext routing = context.routingContext();
        Cookie cookie = routing == null ? null : routing.request().getCookie(PasswordAction.COOKIE);
        String value = cookie == null ? null : cookie.getValue();
        if (!PasskeyAction.kcAction(context.redirectUri(), value, tenantId(context.oidcTenantConfig()))) {
            return;
        }
        context.additionalQueryParams().add("kc_action", PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS);
        routing.response().addCookie(Cookie.cookie(PasswordAction.COOKIE, "").setMaxAge(0).setPath("/"));
    }

    private static String tenantId(OidcTenantConfig config) {
        if (config == null || config.tenantId().isEmpty()) {
            return null;
        }
        return config.tenantId().get();
    }
}
