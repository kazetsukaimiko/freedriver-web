package io.freedriver.app.security;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Compose-network identity for the autonomy MCP process. The Grok Authorization
 * header is not a credential here; a matching internal secret plus subject is.
 */
@ApplicationScoped
public class McpInternalAuthMechanism implements HttpAuthenticationMechanism {

    public static final String SUBJECT_HEADER = "X-Freedriver-Subject";
    public static final String ROLES_HEADER = "X-Freedriver-Roles";
    public static final String SECRET_HEADER = "X-Freedriver-Internal";
    public static final String ATTRIBUTE = "mcp-internal";

    private final String secret;

    public McpInternalAuthMechanism(
            @ConfigProperty(name = "freedriver.mcp.internal-secret") Optional<String> secret) {
        this.secret = secret.map(String::trim).filter(value -> !value.isEmpty()).orElse("");
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (secret.isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        if (BearerHeaders.hasBearer(context.request().getHeader("Authorization"))) {
            return Uni.createFrom().nullItem();
        }
        String provided = context.request().getHeader(SECRET_HEADER);
        if (provided == null || !constantEquals(secret, provided)) {
            return Uni.createFrom().nullItem();
        }
        String subject = context.request().getHeader(SUBJECT_HEADER);
        if (subject == null || subject.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .addAttribute("sub", subject)
                .addAttribute(ATTRIBUTE, Boolean.TRUE);
        String roles = context.request().getHeader(ROLES_HEADER);
        if (roles != null) {
            for (String role : roles.split(",")) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty()) {
                    builder.addRole(trimmed);
                }
            }
        }
        return Uni.createFrom().item(builder.build());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return DEFAULT_PRIORITY + 2000;
    }

    static boolean constantEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
