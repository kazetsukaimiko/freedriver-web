package io.freedriver.app;

import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The {@link Clock} bean in tests. It runs with system time until a test fixes it, and replaces the
 * default system clock. Tests call {@link #useSystemTime()} before each case.
 */
@Singleton
public class TestClock extends Clock {

    private volatile Instant fixed;

    /** Stop the clock at this instant. */
    public void fix(Instant at) {
        fixed = at;
    }

    /** Run with system time again. */
    public void useSystemTime() {
        fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        Instant at = fixed;
        return at == null ? Instant.now() : at;
    }
}
