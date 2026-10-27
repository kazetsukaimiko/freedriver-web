package io.freedriver.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Login links for a phone invite. The host is {@code freedriver.public-base-url} only.
 */
@ApplicationScoped
public class LoginLinks {

    private final String publicBaseUrl;

    @Inject
    public LoginLinks(@ConfigProperty(name = "freedriver.public-base-url", defaultValue = "https://app.freedriver.io")
                      String publicBaseUrl) {
        // Reject a base that cannot be a login-link host before any invite is created.
        SignInLink.url(publicBaseUrl, "+10000000000");
        this.publicBaseUrl = publicBaseUrl;
    }

    public String url(String phone) {
        return SignInLink.url(publicBaseUrl, phone);
    }
}
