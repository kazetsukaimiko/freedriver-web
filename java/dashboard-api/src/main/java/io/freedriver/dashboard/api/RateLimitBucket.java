package io.freedriver.dashboard.api;

/**
 * A dashboard rate-limit bucket. {@link #key()} is the segment stored in the HitWindow key,
 * {@code key() + ":" + caller}.
 */
public enum RateLimitBucket {
    APPLIANCES_COMMANDS("appliances.commands"),
    APPLIANCES_SCHEDULES("appliances.schedules"),
    INSTANCES("instances"),
    INVITES("invites"),
    PHONES("phones");

    private final String key;

    RateLimitBucket(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
