package io.freedriver.dispatcher;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatcher MCP sessions in Redis. The key is {@code freedriver:mcp:dispatcher:}
 * plus the session id, and it expires 24 hours after it is stored.
 */
@ApplicationScoped
class RedisSessionRecords implements McpSessionRecords {

    static final Duration TTL = Duration.ofHours(24);
    static final String PREFIX = "freedriver:mcp:dispatcher:";

    interface Keys {
        void set(String key, String value, long ttlSeconds);

        String get(String key);

        boolean deleteIfValue(String key, String expected);
    }

    private final Keys keys;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    RedisSessionRecords() {
        this.keys = null;
    }

    @Inject
    RedisSessionRecords(
            @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379") String url) {
        this(new LettuceKeys(url));
    }

    RedisSessionRecords(Keys keys) {
        this.keys = keys;
    }

    @Override
    public void insert(String sessionId, String subject) {
        keys.set(PREFIX + sessionId, subject, TTL.toSeconds());
    }

    @Override
    public String subject(String sessionId) {
        return keys.get(PREFIX + sessionId);
    }

    @Override
    public void delete(String sessionId, String subject) {
        keys.deleteIfValue(PREFIX + sessionId, subject);
    }

    /**
     * Lettuce commands. The connection opens on the first call. Lettuce's sync commands are
     * thread-safe, so only the first open is coordinated, and a racing second open is closed.
     */
    static final class LettuceKeys implements Keys {

        private static final String DELETE_IF_VALUE = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                  return redis.call('del', KEYS[1])
                else
                  return 0
                end
                """;

        private final String url;
        private final AtomicReference<RedisCommands<String, String>> commands = new AtomicReference<>();

        LettuceKeys(String url) {
            this.url = url;
        }

        @Override
        public void set(String key, String value, long ttlSeconds) {
            commands().setex(key, ttlSeconds, value);
        }

        @Override
        public String get(String key) {
            return commands().get(key);
        }

        @Override
        public boolean deleteIfValue(String key, String expected) {
            Long removed = commands().eval(
                    DELETE_IF_VALUE, ScriptOutputType.INTEGER, new String[] {key}, expected);
            return removed != null && removed == 1L;
        }

        private RedisCommands<String, String> commands() {
            RedisCommands<String, String> open = commands.get();
            if (open != null) {
                return open;
            }
            RedisClient client = RedisClient.create(url);
            StatefulRedisConnection<String, String> connection = client.connect();
            if (commands.compareAndSet(null, connection.sync())) {
                return commands.get();
            }
            connection.close();
            client.shutdown();
            return commands.get();
        }
    }
}
