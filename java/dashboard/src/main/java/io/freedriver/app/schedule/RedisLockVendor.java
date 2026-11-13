package io.freedriver.app.schedule;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.UUID;

/**
 * A Redis lock. The key the caller passes is the name. The stored key is
 * {@code freedriver:lock:} plus that name. Closing releases it only while this
 * holder still owns the token.
 */
@ApplicationScoped
public class RedisLockVendor {

    private static final String PREFIX = "freedriver:lock:";

    private final Commands commands;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    RedisLockVendor() {
        this.commands = null;
    }

    @Inject
    RedisLockVendor(
            @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379") String url) {
        this.commands = new LettuceCommands(url);
    }

    RedisLockVendor(Commands commands) {
        this.commands = commands;
    }

    public AutoCloseable apply(String key, Duration maxLockLife) {
        if (key == null || key.isBlank() || maxLockLife == null || maxLockLife.isZero() || maxLockLife.isNegative()) {
            throw new IllegalArgumentException("lock");
        }
        String token = UUID.randomUUID().toString();
        String redisKey = PREFIX + key;
        if (!commands.tryHold(redisKey, token, maxLockLife.toMillis())) {
            throw new LockUnavailable();
        }
        return () -> commands.release(redisKey, token);
    }

    interface Commands {
        boolean tryHold(String key, String token, long ttlMillis);

        void release(String key, String token);
    }

    /** Lettuce commands. The connection opens on the first call and resets after a failure. */
    static final class LettuceCommands implements Commands {

        private static final String UNLOCK = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                  return redis.call('del', KEYS[1])
                else
                  return 0
                end
                """;

        private final String url;
        private RedisCommands<String, String> commands;

        LettuceCommands(String url) {
            this.url = url;
        }

        @Override
        public synchronized boolean tryHold(String key, String token, long ttlMillis) {
            try {
                String stored = commands().set(key, token, SetArgs.Builder.nx().px(ttlMillis));
                return "OK".equals(stored);
            } catch (RuntimeException ex) {
                commands = null;
                throw ex;
            }
        }

        @Override
        public synchronized void release(String key, String token) {
            try {
                commands().eval(UNLOCK, ScriptOutputType.INTEGER, new String[] {key}, token);
            } catch (RuntimeException ex) {
                commands = null;
                throw ex;
            }
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
