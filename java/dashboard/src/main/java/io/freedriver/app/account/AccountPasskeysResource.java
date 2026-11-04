package io.freedriver.app.account;

import io.freedriver.dashboard.api.AccountPasskeysApi;
import io.freedriver.dashboard.model.PasskeyActionView;
import io.quarkus.oidc.OidcSession;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Arms Keycloak {@code webauthn-register-passwordless} and drops the local BFF
 * session so {@code /login} starts a new authorization request. The Keycloak SSO
 * cookie stays. WebAuthn never runs in this process.
 */
@ApplicationScoped
@Path("/api/account")
public class AccountPasskeysResource implements AccountPasskeysApi {

    private final Instance<OidcSession> session;
    private final String clientId;

    @Inject
    public AccountPasskeysResource(
            Instance<OidcSession> session,
            @ConfigProperty(name = "quarkus.oidc.client-id") String clientId) {
        this.session = session;
        this.clientId = clientId;
    }

    @Override
    @Blocking
    public PasskeyActionView passkeys() {
        String url = PasswordAction.url(clientId);
        if (session.isResolvable()) {
            session.get().logout().await().atMost(Duration.ofSeconds(5));
        }
        return new PasskeyActionView(url);
    }
}
