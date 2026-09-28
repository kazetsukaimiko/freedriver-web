package io.freedriver.sms.otp;

/** Result of a code check for the REST layer: the Keycloak username on success. */
public record VerifyResult(Status status, String username) {

    public enum Status {
        VERIFIED,
        INVALID_CODE,
        UNAVAILABLE
    }

    static VerifyResult verified(String username) {
        return new VerifyResult(Status.VERIFIED, username);
    }

    static VerifyResult of(Status status) {
        return new VerifyResult(status, null);
    }
}
