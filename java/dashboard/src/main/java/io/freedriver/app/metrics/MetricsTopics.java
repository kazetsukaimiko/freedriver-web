package io.freedriver.app.metrics;

import java.util.UUID;

/**
 * Exact instance metrics topic. Broker ACL may allow {@code freedriver/v1/+/metrics/#}
 * for the API user; this process still subscribes per known house id (no {@code +}/{@code #}).
 */
public final class MetricsTopics {
    public static final int QOS = 1;
    public static final boolean RETAIN = false;

    private MetricsTopics() {}

    public static String metricsTopic(UUID instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId");
        }
        return "freedriver/v1/" + instanceId + "/metrics";
    }
}
