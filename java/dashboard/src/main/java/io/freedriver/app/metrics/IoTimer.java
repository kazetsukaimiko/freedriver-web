package io.freedriver.app.metrics;

/**
 * Name of the Micrometer timer that records blocking IO. A bean class carries
 * {@code @Timed(IoTimer.BLOCKING)}, and the Quarkus Micrometer interceptor tags each call with
 * {@code class}, {@code method} and {@code exception}. Prometheus exports it as
 * {@code freedriver_io_blocking_seconds_count}, {@code _sum} and {@code _max}.
 */
public final class IoTimer {
    public static final String BLOCKING = "freedriver.io.blocking";

    private IoTimer() {}
}
