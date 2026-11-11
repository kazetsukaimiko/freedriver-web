package io.freedriver.ratelimit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding window in Redis. Each key expires with the window, so a Redis restart may reset it.
 * This is the production bean. It does not open a public port.
 */
public final class RedisHitWindow implements HitWindow {

    private static final String TRY_OR_ADD = """
            local key = KEYS[1]
            local index = KEYS[2]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local max = tonumber(ARGV[3])
            local token = ARGV[4]
            local mode = ARGV[5]
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local n = redis.call('ZCARD', key)
            if n == 0 then
              redis.call('DEL', key)
              redis.call('SREM', index, key)
            end
            if mode == 'try' and n >= max then
              if n > 0 then
                redis.call('PEXPIRE', key, window)
              end
              return 0
            end
            redis.call('ZADD', key, now, token)
            redis.call('PEXPIRE', key, window)
            redis.call('SADD', index, key)
            return 1
            """;

    private static final String COUNT = """
            local key = KEYS[1]
            local index = KEYS[2]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
            local n = redis.call('ZCARD', key)
            if n == 0 then
              redis.call('DEL', key)
              redis.call('SREM', index, key)
            else
              redis.call('PEXPIRE', key, window)
            end
            return n
            """;

    private final String url;
    private final String index;
    private final AtomicLong sequence = new AtomicLong();
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;

    public RedisHitWindow(String url, String index) {
        this.url = url;
        this.index = index;
    }

    @Override
    public String tryAdd(String key, long nowMs, long windowMs, int max) {
        String token = token(nowMs);
        long added = eval(TRY_OR_ADD, key, nowMs, windowMs, max, token, "try");
        return added == 1 ? token : null;
    }

    @Override
    public String add(String key, long nowMs, long windowMs) {
        String token = token(nowMs);
        eval(TRY_OR_ADD, key, nowMs, windowMs, Integer.MAX_VALUE, token, "add");
        return token;
    }

    @Override
    public int count(String key, long nowMs, long windowMs) {
        Long n = commands().eval(COUNT, ScriptOutputType.INTEGER, new String[] {key, index},
                Long.toString(nowMs), Long.toString(windowMs));
        return n == null ? 0 : n.intValue();
    }

    @Override
    public void remove(String key, String token) {
        RedisCommands<String, String> redis = commands();
        redis.zrem(key, token);
        Long n = redis.zcard(key);
        if (n == null || n == 0) {
            redis.del(key);
            redis.srem(index, key);
        }
    }

    @Override
    public void delete(String key) {
        RedisCommands<String, String> redis = commands();
        redis.del(key);
        redis.srem(index, key);
    }

    @Override
    public void clear() {
        RedisCommands<String, String> redis = commands();
        Set<String> keys = redis.smembers(index);
        if (keys != null && !keys.isEmpty()) {
            redis.del(keys.toArray(String[]::new));
        }
        redis.del(index);
    }

    @Override
    public int size(String prefix, long nowMs, long windowMs) {
        Set<String> keys = commands().smembers(index);
        if (keys == null) {
            return 0;
        }
        int live = 0;
        for (String key : keys) {
            if (key.startsWith(prefix) && count(key, nowMs, windowMs) > 0) {
                live++;
            }
        }
        return live;
    }

    private long eval(String script, String key, long nowMs, long windowMs, int max, String token, String mode) {
        Long result = commands().eval(script, ScriptOutputType.INTEGER, new String[] {key, index},
                Long.toString(nowMs), Long.toString(windowMs), Integer.toString(max), token, mode);
        return result == null ? 0 : result;
    }

    private String token(long nowMs) {
        return nowMs + ":" + sequence.incrementAndGet();
    }

    private RedisCommands<String, String> commands() {
        RedisCommands<String, String> current = commands;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (commands == null) {
                client = RedisClient.create(url);
                connection = client.connect();
                commands = connection.sync();
            }
            return commands;
        }
    }
}
