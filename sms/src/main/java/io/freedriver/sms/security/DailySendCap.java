package io.freedriver.sms.security;

import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Service-wide cap per UTC day on sends that reach the provider, on top of the per-number limits.
 * The count lives in {@link DailySendLedger}, shared by every replica. When it is reached, no more codes are sent until the
 * next UTC day and the service logs an error.
 */
@ApplicationScoped
public class DailySendCap {

    private static final Logger LOG = Logger.getLogger(DailySendCap.class);

    private final Clock clock;
    private final int cap;
    private final DailySendLedger ledger;
    private LocalDate announcedDay;
    private boolean announced;

    @Inject
    public DailySendCap(SmsConfig config, DailySendLedger ledger) {
        this(Clock.systemUTC(), config.dailySendCap(), ledger);
    }

    DailySendCap(Clock clock, int cap, DailySendLedger ledger) {
        this.clock = clock;
        this.cap = cap;
        this.ledger = ledger;
    }

    public boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        boolean allowed = ledger.tryAcquire(today, cap);
        if (!allowed) {
            announce(today);
        }
        return allowed;
    }

    private synchronized void announce(LocalDate today) {
        if (!today.equals(announcedDay)) {
            announcedDay = today;
            announced = false;
        }
        if (!announced) {
            announced = true;
            LOG.errorf("SMS daily send cap of %d reached for %s UTC. No codes will be sent until 00:00 UTC.", cap, today);
        }
    }

    public void reset() {
        synchronized (this) {
            announcedDay = null;
            announced = false;
        }
        ledger.clear();
    }
}
