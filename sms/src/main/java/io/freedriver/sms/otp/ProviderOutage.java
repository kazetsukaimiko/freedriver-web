package io.freedriver.sms.otp;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Remembers how long the last provider check took when that check errored or timed out, in Redis
 * key {@code freedriver:sms:provider-outage}, so every sms replica reads the same memory. An
 * unlisted number is not sent. While the key is set, that number waits the same duration and then
 * fails unavailable. An answer from the provider deletes it, and the key expires with the
 * five-minute code window.
 */
@ApplicationScoped
public class ProviderOutage {

    static final String KEY = "freedriver:sms:provider-outage";
    static final Duration TTL = VerificationLedger.TTL;

    private final RedisDataSource redis;
    private final ValueCommands<String, Long> values;

    @Inject
    ProviderOutage(RedisDataSource redis) {
        this.redis = redis;
        this.values = redis.value(Long.class);
    }

    public void answered() {
        redis.key().del(KEY);
    }

    public void failed(Duration elapsed) {
        Duration wait = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        values.setex(KEY, TTL.toSeconds(), wait.toMillis());
    }

    /**
     * @return true when the provider's last check failed, after waiting that long
     */
    public boolean waitIfDown() {
        Long waitMillis = values.get(KEY);
        if (waitMillis == null) {
            return false;
        }
        if (waitMillis > 0) {
            LockSupport.parkNanos(Duration.ofMillis(waitMillis).toNanos());
            if (Thread.interrupted()) {
                throw new SenderUnavailableException();
            }
        }
        return true;
    }
}
