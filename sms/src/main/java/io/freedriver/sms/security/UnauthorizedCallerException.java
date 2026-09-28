package io.freedriver.sms.security;

/** The request did not carry Keycloak's shared secret, or the service has no usable secret. */
public class UnauthorizedCallerException extends RuntimeException {

    public UnauthorizedCallerException() {
        super(null, null, false, false);
    }
}
