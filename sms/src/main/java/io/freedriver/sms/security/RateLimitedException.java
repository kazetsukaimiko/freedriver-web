package io.freedriver.sms.security;

import java.time.Duration;

/** A number used up its sends or wrong codes for the window. */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(null, null, false, false);
        this.retryAfter = retryAfter;
    }

    /** How long the caller waits before the number's window has fully cleared. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
