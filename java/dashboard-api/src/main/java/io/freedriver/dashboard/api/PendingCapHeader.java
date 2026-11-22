package io.freedriver.dashboard.api;

/**
 * The mark on the empty 429 from a full pending scheduled-switch cap.
 * A spent {@link RateLimitBucket#APPLIANCES_SCHEDULES} window leaves this header off the response.
 */
public final class PendingCapHeader {

    /** Response header that carries {@link #FULL} when the pending cap refused the store. */
    public static final String NAME = "X-Freedriver-Pending-Cap";

    /** The only value that means the pending cap is full. Any other value leaves the cap unmarked. */
    public static final String FULL = "full";

    private PendingCapHeader() {
    }
}
