package io.freedriver.sms.model;

/** Why a verify call was rejected. Listed and unlisted numbers use the same three values. */
public final class VerifyFailure {

    public static final String EXPIRED = "expired";
    public static final String WRONG_CODE = "wrong_code";
    public static final String TOO_MANY_ATTEMPTS = "too_many_attempts";

    private VerifyFailure() {
    }
}
