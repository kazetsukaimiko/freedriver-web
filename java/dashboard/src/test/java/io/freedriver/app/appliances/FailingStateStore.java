package io.freedriver.app.appliances;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import java.util.concurrent.atomic.AtomicBoolean;

/** Fails the state write while {@link #failing} is set, as a Mongo outage would. */
@Singleton
public class FailingStateStore {

    static final AtomicBoolean failing = new AtomicBoolean();

    void onState(@Observes ApplianceStateRouted routed) {
        if (failing.get()) {
            throw new IllegalStateException("store unavailable");
        }
    }
}
