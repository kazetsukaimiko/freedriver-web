package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;

/** The phone already belongs to a Keycloak user. */
public class PhoneAlreadyExists extends InviteUpstreamException {

    public PhoneAlreadyExists() {
        super("phone already has a user");
    }
}
