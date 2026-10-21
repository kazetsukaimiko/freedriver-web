package io.freedriver.keycloak.sms;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

final class InviteAccountResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    InviteAccountResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return new InviteAccountResource(session);
    }

    @Override
    public void close() {
    }
}
