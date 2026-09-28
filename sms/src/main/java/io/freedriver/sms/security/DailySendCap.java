package io.freedriver.sms.security;

import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Service-wide cap on sends per UTC day, on top of the per-number limits. When it is reached,
 * sends stop until the next UTC day and the service logs an error.
 */
@ApplicationScoped
public class DailySendCap {

    private static final Logger LOG = Logger.getLogger(DailySendCap.class);

    private final Clock clock;
    private final int cap;
    private LocalDate day;
    private int used;
    private boolean announced;

    @Inject
    public DailySendCap(SmsConfig config) {
        this(Clock.systemUTC(), config.dailySendCap());
    }

    DailySendCap(Clock clock, int cap) {
        this.clock = clock;
        this.cap = cap;
    }

    public synchronized boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) {
            day = today;
            used = 0;
            announced = false;
        }
        if (used >= cap) {
            if (!announced) {
                announced = true;
                LOG.errorf("SMS daily send cap of %d reached for %s UTC. No codes will be sent until 00:00 UTC.", cap, today);
            }
            return false;
        }
        used++;
        return true;
    }

    public synchronized void reset() {
        day = null;
        used = 0;
        announced = false;
    }
}
