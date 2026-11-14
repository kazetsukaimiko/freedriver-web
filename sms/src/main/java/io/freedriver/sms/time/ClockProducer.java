package io.freedriver.sms.time;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

/** The system UTC clock. A test that needs to move time declares its own {@link Clock} bean, which replaces this one. */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @Singleton
    @DefaultBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
