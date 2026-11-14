package io.freedriver.sms.support;

import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The {@link Clock} bean in tests, which replaces the default system clock. It runs with system time
 * plus an offset until a test fixes it. A test that fixes or moves it puts it back with
 * {@link #useSystemTime()}.
 */
@Singleton
public class TestClock extends Clock {

    private volatile Instant fixed;
    private volatile Duration offset = Duration.ZERO;

    /** Stop the clock at this instant. */
    public synchronized void fix(Instant at) {
        fixed = at;
    }

    /** Move the clock forward, fixed or running. */
    public synchronized void advance(Duration by) {
        if (fixed != null) {
            fixed = fixed.plus(by);
        } else {
            offset = offset.plus(by);
        }
    }

    /** Move one day ahead, so the daily send cap starts a new, empty UTC day. */
    public void nextDay() {
        advance(Duration.ofDays(1));
    }

    /** Run with system time again. */
    public synchronized void useSystemTime() {
        fixed = null;
        offset = Duration.ZERO;
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
        return at == null ? Instant.now().plus(offset) : at;
    }
}
