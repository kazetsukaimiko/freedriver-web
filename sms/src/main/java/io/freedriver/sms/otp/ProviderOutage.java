package io.freedriver.sms.otp;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Remembers how long the last provider check took when that check errored or timed out.
 * An unlisted number is not sent. While this memory is set, that number waits the same
 * duration and then fails unavailable. An answer from the provider clears it.
 */
@ApplicationScoped
public class ProviderOutage {

    private final AtomicReference<Duration> failedCall = new AtomicReference<>();

    public void answered() {
        failedCall.set(null);
    }

    public void failed(Duration elapsed) {
        Duration wait = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        failedCall.set(wait);
    }

    /**
     * @return true when the provider's last check failed, after waiting that long
     */
    public boolean waitIfDown() {
        Duration wait = failedCall.get();
        if (wait == null) {
            return false;
        }
        long nanos = wait.toNanos();
        if (nanos > 0) {
            LockSupport.parkNanos(nanos);
            if (Thread.interrupted()) {
                throw new SenderUnavailableException();
            }
        }
        return true;
    }

    public void reset() {
        failedCall.set(null);
    }
}
