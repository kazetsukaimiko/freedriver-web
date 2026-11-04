package io.freedriver.app.account;

import io.freedriver.dashboard.model.PasskeyActionView;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * HttpOnly one-shot cookie that {@link PasskeyActionRedirectFilter} turns into
 * {@code kc_action=webauthn-register-passwordless}.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.HEADER_DECORATOR)
public class PasskeyActionCookieFilter implements ContainerResponseFilter {

    private final boolean cookieSecure;

    @Inject
    public PasskeyActionCookieFilter(
            @ConfigProperty(name = "freedriver.csrf.cookie-secure", defaultValue = "false") boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!(response.getEntity() instanceof PasskeyActionView)) {
            return;
        }
        response.getHeaders().add(
                "Set-Cookie",
                new NewCookie.Builder(PasswordAction.COOKIE)
                        .value(PasskeyAction.WEBAUTHN_REGISTER_PASSWORDLESS)
                        .path("/")
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .sameSite(NewCookie.SameSite.LAX)
                        .maxAge(300)
                        .build());
    }
}
