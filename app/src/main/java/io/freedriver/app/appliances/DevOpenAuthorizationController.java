package io.freedriver.app.appliances;

/**
 * Not a CDI bean. {@code %dev.quarkus.security.auth.enabled-in-dev-mode=false}
 * already installs Quarkus's DevModeDisabledAuthorizationController. A second
 * AuthorizationController here made quarkus:dev fail to start.
 */
public final class DevOpenAuthorizationController {
    private DevOpenAuthorizationController() {
    }
}
