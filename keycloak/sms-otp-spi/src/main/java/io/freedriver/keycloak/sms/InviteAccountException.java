package io.freedriver.keycloak.sms;

/** The invite service account could not create this phone user. */
final class InviteAccountException extends RuntimeException {

    private final int status;

    InviteAccountException(int status) {
        this.status = status;
    }

    int status() {
        return status;
    }
}
