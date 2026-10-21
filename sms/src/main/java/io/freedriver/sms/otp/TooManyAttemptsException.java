package io.freedriver.sms.otp;

import java.time.Duration;

/** The check after the fifth attempt on the open verification. */
public final class TooManyAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super(null, null, false, false);
        this.retryAfter = retryAfter;
    }

    /** Same {@code Retry-After} window the per-number send limit uses. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
