package io.freedriver.app.metrics;

import io.micrometer.core.annotation.Timed;
import jakarta.enterprise.context.ApplicationScoped;

/** Timed on the class with nothing on its methods, as the six blocking-IO beans are. */
@Timed(IoTimer.BLOCKING)
@ApplicationScoped
public class TimedIoProbe {
    static final String PHONE = "+15555550123";
    static final String TOKEN = "sk-live-9f8e7d6c5b4a";
    static final String MESSAGE = "sms to " + PHONE + " refused token " + TOKEN;

    public void ping() {}

    public void fail() {
        throw new IllegalStateException(MESSAGE);
    }
}
