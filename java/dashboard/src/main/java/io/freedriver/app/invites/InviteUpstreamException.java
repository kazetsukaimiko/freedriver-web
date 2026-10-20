package io.freedriver.app.invites;

/** sms or Keycloak refused a phone-list change. */
public class InviteUpstreamException extends RuntimeException {

    public InviteUpstreamException(String message) {
        super(message);
    }

    public InviteUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
