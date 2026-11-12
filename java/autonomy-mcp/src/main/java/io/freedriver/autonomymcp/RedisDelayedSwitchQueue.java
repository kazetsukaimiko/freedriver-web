package io.freedriver.autonomymcp;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Delayed switches in Redis. The due time is a score on
 * {@code freedriver:mcp:autonomy:switch-due}. The payload is a separate key
 * with a TTL, so a restarted autonomy-mcp process can still fire the switch.
 */
@ApplicationScoped
class RedisDelayedSwitchQueue implements DelayedSwitchQueue {

    private final SwitchDelayRecords records;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    RedisDelayedSwitchQueue() {
        this.records = null;
    }

    @Inject
    RedisDelayedSwitchQueue(
            @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379") String url) {
        this.records = new SwitchDelayRecords(new LettuceKeys(url));
    }

    @Override
    public void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds) {
        records.schedule(id, dueEpochMillis, payload, ttlSeconds);
    }

    @Override
    public String lockDue(long nowEpochMillis) {
        return records.lockDue(nowEpochMillis);
    }

    @Override
    public String payload(String id) {
        return records.payload(id);
    }

    @Override
    public void remove(String id) {
        records.remove(id);
    }

    /** Lettuce commands. The connection opens on the first call. */
    static final class LettuceKeys implements SwitchDelayRecords.Keys {

        private final String url;
        private RedisCommands<String, String> commands;

        LettuceKeys(String url) {
            this.url = url;
        }

        @Override
        public synchronized void schedule(String id, long dueEpochMillis, String payload, long ttlSeconds) {
            RedisCommands<String, String> redis = commands();
            redis.multi();
            redis.zadd(SwitchDelay.DUE_KEY, dueEpochMillis, id);
            redis.setex(SwitchDelay.PAYLOAD_PREFIX + id, ttlSeconds, payload);
            TransactionResult result = redis.exec();
            if (result == null || result.wasDiscarded()) {
                throw new IllegalStateException("delayed switch was not stored");
            }
        }

        @Override
        public synchronized List<String> dueIds(long nowEpochMillis, int limit) {
            return commands().zrangebyscore(
                    SwitchDelay.DUE_KEY,
                    Range.create(Double.NEGATIVE_INFINITY, (double) nowEpochMillis),
                    Limit.create(0, limit));
        }

        @Override
        public synchronized boolean tryLock(String id, long nowEpochMillis, long lockSeconds) {
            String stored = commands().set(
                    SwitchDelay.LOCK_PREFIX + id,
                    "1",
                    SetArgs.Builder.nx().ex(lockSeconds));
            return "OK".equals(stored);
        }

        @Override
        public synchronized String payload(String id) {
            return commands().get(SwitchDelay.PAYLOAD_PREFIX + id);
        }

        @Override
        public synchronized void remove(String id) {
            RedisCommands<String, String> redis = commands();
            redis.zrem(SwitchDelay.DUE_KEY, id);
            redis.del(SwitchDelay.PAYLOAD_PREFIX + id, SwitchDelay.LOCK_PREFIX + id);
        }

        private RedisCommands<String, String> commands() {
            if (commands == null) {
                RedisClient client = RedisClient.create(url);
                StatefulRedisConnection<String, String> connection = client.connect();
                commands = connection.sync();
            }
            return commands;
        }
    }
}
