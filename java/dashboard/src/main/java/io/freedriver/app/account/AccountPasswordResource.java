package io.freedriver.app.account;

import io.freedriver.dashboard.api.AccountPasswordApi;
import io.freedriver.dashboard.model.PasswordActionView;
import io.quarkus.oidc.OidcSession;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Arms Keycloak {@code UPDATE_PASSWORD} and drops the local BFF session so
 * {@code /login} starts a new authorization request. The Keycloak SSO cookie
 * stays. The new password never enters this process. {@link OidcSession} is
 * absent while OIDC is off. The one-shot cookie is {@link PasswordActionCookieFilter}.
 */
@ApplicationScoped
@Path("/api/account")
public class AccountPasswordResource implements AccountPasswordApi {

    private final Instance<OidcSession> session;
    private final String clientId;

    @Inject
    public AccountPasswordResource(
            Instance<OidcSession> session,
            @ConfigProperty(name = "quarkus.oidc.client-id") String clientId) {
        this.session = session;
        this.clientId = clientId;
    }

    @Override
    @Blocking
    public PasswordActionView password() {
        String url = PasswordAction.url(clientId);
        if (session.isResolvable()) {
            session.get().logout().await().atMost(Duration.ofSeconds(5));
        }
        return new PasswordActionView(url);
    }
}
