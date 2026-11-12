package io.freedriver.sms.otp;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Open SMS codes in Redis. The key is {@code freedriver:sms:code:} plus the
 * phone number, and it expires five minutes after the send.
 */
@ApplicationScoped
class RedisVerificationRecords implements VerificationRecords {

    static final String PREFIX = "freedriver:sms:code:";
    static final String INDEX = "freedriver:sms:code-index";

    interface Hashes {
        /** Stores the hash and returns the previous sid, or null. */
        String replace(String key, Map<String, String> fields, long ttlSeconds);

        Map<String, String> read(String key);

        void write(String key, Map<String, String> fields, long ttlSeconds);

        void delete(String key);

        void deleteAll();
    }

    private final Hashes hashes;

    /** Arc builds the client proxy with this constructor. */
    RedisVerificationRecords() {
        this.hashes = null;
    }

    @Inject
    RedisVerificationRecords(
            @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379") String url) {
        this(new LettuceHashes(url));
    }

    RedisVerificationRecords(Hashes hashes) {
        this.hashes = hashes;
    }

    @Override
    public String replace(String phone, Instant startedAt, Instant expiresAt) {
        return hashes.replace(PREFIX + phone, fields(new Open(startedAt, expiresAt, null, false)), ttl(startedAt, expiresAt));
    }

    @Override
    public void rememberSid(String phone, String sid) {
        Open current = read(phone);
        if (current == null) {
            return;
        }
        hashes.write(PREFIX + phone, fields(current.withSid(sid)), ttl(current.startedAt(), current.expiresAt()));
    }

    @Override
    public Open read(String phone) {
        Map<String, String> stored = hashes.read(PREFIX + phone);
        if (stored == null || stored.isEmpty() || stored.get("startedAt") == null) {
            return null;
        }
        String sid = stored.get("sid");
        return new Open(
                Instant.ofEpochMilli(Long.parseLong(stored.get("startedAt"))),
                Instant.ofEpochMilli(Long.parseLong(stored.get("expiresAt"))),
                sid == null || sid.isEmpty() ? null : sid,
                "true".equals(stored.get("exhausted")));
    }

    @Override
    public void dropSid(String phone) {
        Open current = read(phone);
        if (current == null) {
            return;
        }
        hashes.write(PREFIX + phone, fields(current.withoutSid()), ttl(current.startedAt(), current.expiresAt()));
    }

    @Override
    public void exhaust(String phone) {
        Open current = read(phone);
        if (current == null) {
            return;
        }
        hashes.write(PREFIX + phone, fields(current.exhaust()), ttl(current.startedAt(), current.expiresAt()));
    }

    @Override
    public void clear(String phone) {
        hashes.delete(PREFIX + phone);
    }

    @Override
    public void clearAll() {
        hashes.deleteAll();
    }

    private static long ttl(Instant startedAt, Instant expiresAt) {
        return Math.max(1, Duration.between(startedAt, expiresAt).getSeconds());
    }

    private static Map<String, String> fields(Open open) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("startedAt", Long.toString(open.startedAt().toEpochMilli()));
        fields.put("expiresAt", Long.toString(open.expiresAt().toEpochMilli()));
        fields.put("exhausted", Boolean.toString(open.exhausted()));
        if (open.sid() != null) {
            fields.put("sid", open.sid());
        }
        return fields;
    }

    /** Lettuce hashes. The connection opens on the first call. */
    static final class LettuceHashes implements Hashes {

        private final String url;
        private RedisCommands<String, String> commands;

        LettuceHashes(String url) {
            this.url = url;
        }

        @Override
        public synchronized String replace(String key, Map<String, String> fields, long ttlSeconds) {
            String previous = commands().hget(key, "sid");
            write(key, fields, ttlSeconds);
            return previous == null || previous.isEmpty() ? null : previous;
        }

        @Override
        public synchronized Map<String, String> read(String key) {
            return commands().hgetall(key);
        }

        @Override
        public synchronized void write(String key, Map<String, String> fields, long ttlSeconds) {
            RedisCommands<String, String> redis = commands();
            redis.del(key);
            redis.hset(key, fields);
            redis.expire(key, ttlSeconds);
            redis.sadd(INDEX, key);
        }

        @Override
        public synchronized void delete(String key) {
            RedisCommands<String, String> redis = commands();
            redis.del(key);
            redis.srem(INDEX, key);
        }

        @Override
        public synchronized void deleteAll() {
            RedisCommands<String, String> redis = commands();
            Set<String> keys = redis.smembers(INDEX);
            if (keys != null && !keys.isEmpty()) {
                redis.del(keys.toArray(String[]::new));
            }
            redis.del(INDEX);
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
