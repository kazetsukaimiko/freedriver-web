package io.freedriver.app.schedule;

import io.freedriver.app.appliances.ApplianceAudit;
import io.freedriver.app.appliances.ApplianceService;
import io.freedriver.dashboard.model.InstanceView;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Fires due switches. Each pass claims one row at a time from Mongo and runs it, so replicas
 * work in parallel and Mongo is the only dependency. A row whose command is confirmed is deleted.
 * A row whose command fails or times out is claimed again when its lease ends, up to
 * {@link #MAX_ATTEMPTS}; after that it is deleted and the audit records {@code failed}.
 */
@ApplicationScoped
public class ScheduledSwitchPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSwitchPoller.class);
    /** One command takes at most 30 seconds. */
    static final Duration LEASE = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 3;
    static final int ROWS_PER_PASS = 8;

    private final ScheduledSwitchRepository rows;
    private final ApplianceService appliances;
    private final ApplianceAudit audit;
    private final Clock clock;

    @Inject
    ScheduledSwitchPoller(ScheduledSwitchRepository rows, ApplianceService appliances, ApplianceAudit audit) {
        this(rows, appliances, audit, Clock.systemUTC());
    }

    ScheduledSwitchPoller(ScheduledSwitchRepository rows, ApplianceService appliances, ApplianceAudit audit,
                          Clock clock) {
        this.rows = rows;
        this.appliances = appliances;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            pass();
        } catch (RuntimeException ex) {
            log.warn("scheduled switch pass failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Claims and runs rows until none is free or {@link #ROWS_PER_PASS} have been claimed. */
    int pass() {
        int claimed = 0;
        while (claimed < ROWS_PER_PASS) {
            ScheduledSwitch row = rows.claim(clock.instant(), LEASE);
            if (row == null) {
                break;
            }
            claimed++;
            handle(row);
        }
        return claimed;
    }

    private void handle(ScheduledSwitch row) {
        if (row.attempts > MAX_ATTEMPTS) {
            // A replica stopped during the last attempt. Nothing runs again.
            fail(row);
            return;
        }
        if (run(row)) {
            rows.deleteById(row.id);
        } else if (row.attempts >= MAX_ATTEMPTS) {
            fail(row);
        }
    }

    /** True when the command was confirmed. A refusal, an error, or a timeout is false. */
    private boolean run(ScheduledSwitch row) {
        try {
            InstanceView view = appliances.issueCommand(
                    UUID.fromString(row.instanceId), row.applianceName, row.on, row.subject);
            return !view.timeout();
        } catch (RuntimeException ex) {
            log.warn("scheduled switch failed: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private void fail(ScheduledSwitch row) {
        rows.deleteById(row.id);
        UUID instanceId;
        try {
            instanceId = UUID.fromString(row.instanceId);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("scheduled switch {} dropped: unreadable instance id", row.id);
            return;
        }
        audit.record(new ApplianceAudit.Event(
                clock.instant(), instanceId, row.applianceName, row.on, row.id, "failed", row.subject));
    }
}
