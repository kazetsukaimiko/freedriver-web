package io.freedriver.mcp;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

/**
 * MCP session bindings on the service's managed Redis client. The key is
 * {@code freedriver.mcp.session-prefix} plus the session id, it holds the subject, and it expires
 * 24 hours after it is written. A delete is one script, so it removes only a key bound to that subject.
 */
@ApplicationScoped
public class RedisSessionRecords implements McpSessionRecords {

    static final Duration TTL = Duration.ofHours(24);

    private static final String DELETE_IF_VALUE = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """;

    private final ReactiveRedisDataSource redis;
    private final ReactiveValueCommands<String, String> values;
    private final String prefix;

    @Inject
    public RedisSessionRecords(ReactiveRedisDataSource redis, McpEndpointConfig config) {
        this(redis, config.sessionPrefix());
    }

    public RedisSessionRecords(ReactiveRedisDataSource redis, String prefix) {
        this.redis = redis;
        this.values = redis.value(String.class);
        this.prefix = prefix;
    }

    @Override
    public Uni<Void> insert(String sessionId, String subject) {
        return values.setex(prefix + sessionId, TTL.toSeconds(), subject);
    }

    @Override
    public Uni<String> subject(String sessionId) {
        return values.get(prefix + sessionId);
    }

    @Override
    public Uni<Void> delete(String sessionId, String subject) {
        return redis.execute("EVAL", DELETE_IF_VALUE, "1", prefix + sessionId, subject).replaceWithVoid();
    }
}
