package io.freedriver.autonomymcp;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;

/**
 * With OIDC off, a {@code @TestSecurity} caller has no access token. This stands in for the one
 * quarkus-oidc would inject: {@code caller-<name>}, so a test can see which caller was exchanged.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkus.oidc.enabled", stringValue = "false")
public class TestCallerToken {

    static String of(String user) {
        return "caller-" + user;
    }

    @Produces
    @RequestScoped
    AccessTokenCredential callerToken(SecurityIdentity identity) {
        return new AccessTokenCredential(of(identity.getPrincipal().getName()));
    }
}
