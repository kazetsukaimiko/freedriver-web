package io.freedriver.architecture.fixtures.apiquality;

import io.micrometer.core.instrument.MeterRegistry;

/** A service class that depends on Micrometer. */
public class ServiceMetrics {
    MeterRegistry registry;
}
