package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;

import java.util.Optional;

/**
 * The second Keycloak admin client, for {@code freedriver-invite}. The injected default
 * {@code Keycloak} is {@code freedriver-instances}. The client fetches and reuses its own token.
 * A blank secret is refused here, so no blank secret reaches the token endpoint.
 */
@ApplicationScoped
public class InviteAdminProducer {

    @Produces
    @Singleton
    @InviteAdmin
    Keycloak inviteAdmin(
            @ConfigProperty(name = "freedriver.keycloak.server-url") String serverUrl,
            @ConfigProperty(name = "quarkus.keycloak.admin-client.realm") String realm,
            @ConfigProperty(name = "freedriver.invite.client-id") String clientId,
            @ConfigProperty(name = "freedriver.invite.client-secret") Optional<String> clientSecret) {
        String secret = clientSecret.map(String::strip).filter(value -> !value.isEmpty())
                .orElseThrow(() -> new InviteUpstreamException("invite client secret is unset"));
        return KeycloakBuilder.builder()
                .serverUrl(serverUrl)
                .realm(realm)
                .clientId(clientId)
                .clientSecret(secret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }

    void close(@Disposes @InviteAdmin Keycloak keycloak) {
        keycloak.close();
    }
}
