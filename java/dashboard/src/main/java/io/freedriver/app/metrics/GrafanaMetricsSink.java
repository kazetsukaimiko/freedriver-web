package io.freedriver.app.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the Grafana metrics sink so the blocking-IO interceptor (#346) can skip
 * these calls and avoid recursion.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GrafanaMetricsSink {}
