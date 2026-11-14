package io.freedriver.ratelimit;

import io.quarkus.redis.datasource.RedisDataSource;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * Sliding windows as Redis sorted sets on the service's managed client. Each key is
 * {@code freedriver:rate:<namespace>:<key>}, scored by the Redis {@code TIME} of each hit, with
 * a random UUID member. A key expires with its window, so a Redis restart may reset it.
 */
@ApplicationScoped
public class RedisHitWindow implements HitWindow {

    private static final String NOW = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            """;

    /** ARGV: window, max, member, mode (try or add). Returns 1 when the hit was added. */
    private static final String TRY_OR_ADD = NOW + """
            local key = KEYS[1]
            local window = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            if ARGV[4] == 'try' and redis.call('ZCARD', key) >= tonumber(ARGV[2]) then
              return 0
            end
            redis.call('ZADD', key, now, ARGV[3])
            redis.call('PEXPIRE', key, window)
            return 1
            """;

    /** ARGV: window. Returns the hits inside the window. */
    private static final String COUNT = NOW + """
            local key = KEYS[1]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - tonumber(ARGV[1]))
            return redis.call('ZCARD', key)
            """;

    private final RedisDataSource redis;
    private final String prefix;

    @Inject
    public RedisHitWindow(RedisDataSource redis,
                          @ConfigProperty(name = "freedriver.rate-limit.namespace") String namespace) {
        this.redis = redis;
        this.prefix = "freedriver:rate:" + namespace + ":";
    }

    @Override
    public String tryAdd(String key, long windowMs, int max) {
        String token = UUID.randomUUID().toString();
        return eval(TRY_OR_ADD, key, Long.toString(windowMs), Integer.toString(max), token, "try") == 1 ? token : null;
    }

    @Override
    public String add(String key, long windowMs) {
        String token = UUID.randomUUID().toString();
        eval(TRY_OR_ADD, key, Long.toString(windowMs), "0", token, "add");
        return token;
    }

    @Override
    public int count(String key, long windowMs) {
        return (int) eval(COUNT, key, Long.toString(windowMs));
    }

    /** Redis drops a sorted set once its last member is removed. */
    @Override
    public void remove(String key, String token) {
        redis.sortedSet(String.class).zrem(prefix + key, token);
    }

    @Override
    public void delete(String key) {
        redis.key().del(prefix + key);
    }

    private long eval(String script, String key, String... args) {
        String[] command = new String[3 + args.length];
        command[0] = script;
        command[1] = "1";
        command[2] = prefix + key;
        System.arraycopy(args, 0, command, 3, args.length);
        Response result = redis.execute("EVAL", command);
        return result == null ? 0 : result.toLong();
    }
}
