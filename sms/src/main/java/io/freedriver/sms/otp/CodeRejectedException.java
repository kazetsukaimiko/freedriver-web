package io.freedriver.sms.otp;

/**
 * A code check that did not sign in. {@code reason} is {@code expired} or {@code wrong_code}
 * when the verify response names one. A malformed code has no reason.
 */
public class CodeRejectedException extends RuntimeException {

    private final String reason;

    public CodeRejectedException() {
        this(null);
    }

    public CodeRejectedException(String reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
