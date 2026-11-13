package io.freedriver.app.schedule;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires due switches off the request thread. One replica holds the lock for the pass.
 */
@ApplicationScoped
public class ScheduledSwitchPoller {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSwitchPoller.class);
    static final Duration LOCK_LIFE = Duration.ofSeconds(45);
    /** One command can take the configured maximum, which is 30 seconds. */
    static final Duration COMMAND_BUDGET = Duration.ofSeconds(35);

    private final ScheduledSwitches store;
    private final SwitchRunner runner;
    private final RedisLockVendor locks;
    private final Clock clock;
    private final boolean poll;
    private ScheduledExecutorService executor;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    ScheduledSwitchPoller() {
        this.store = null;
        this.runner = null;
        this.locks = null;
        this.clock = null;
        this.poll = false;
    }

    @Inject
    ScheduledSwitchPoller(
            ScheduledSwitches store,
            SwitchRunner runner,
            RedisLockVendor locks,
            @ConfigProperty(name = "freedriver.scheduled-switches.poll", defaultValue = "true") boolean poll) {
        this(store, runner, locks, Clock.systemUTC(), poll);
    }

    ScheduledSwitchPoller(
            ScheduledSwitches store, SwitchRunner runner, RedisLockVendor locks, Clock clock, boolean poll) {
        this.store = store;
        this.runner = runner;
        this.locks = locks;
        this.clock = clock;
        this.poll = poll;
    }

    @PostConstruct
    void start() {
        if (!poll) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scheduled-switch");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Runs switches that are due at {@code now}. A held lock leaves them for the next pass. */
    public void poll(Instant now) {
        Instant deadline = now.plus(LOCK_LIFE).minus(COMMAND_BUDGET);
        try (AutoCloseable ignored = locks.apply(ScheduledSwitchPoller.class.getSimpleName(), LOCK_LIFE)) {
            for (ScheduledSwitch row : store.due(now)) {
                if (!clock.instant().isBefore(deadline)) {
                    return;
                }
                try {
                    runner.run(row);
                } catch (RuntimeException ex) {
                    log.warn("scheduled switch failed: {}", ex.getClass().getSimpleName());
                }
                store.delete(row.id());
            }
        } catch (LockUnavailable ex) {
            return;
        } catch (Exception ex) {
            log.warn("scheduled switch poll failed: {}", ex.getClass().getSimpleName());
        }
    }

    private void tick() {
        try {
            poll(Instant.now());
        } catch (Exception ex) {
            log.warn("scheduled switch tick failed: {}", ex.getClass().getSimpleName());
        }
    }
}
