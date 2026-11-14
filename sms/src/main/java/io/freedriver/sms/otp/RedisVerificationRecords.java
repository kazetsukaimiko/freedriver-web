package io.freedriver.sms.otp;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Open SMS codes in Redis, one hash per verification. The key is {@code freedriver:sms:code:} plus
 * the phone number, and it expires five minutes after the send. Each change is one command or one
 * script, so two sms replicas never overwrite each other's field.
 */
@ApplicationScoped
class RedisVerificationRecords implements VerificationRecords {

    static final String PREFIX = "freedriver:sms:code:";

    /** KEYS[1] the hash; ARGV startedAt, expiresAt, ttl seconds. Returns the previous sid or false. */
    private static final String REPLACE = """
            local previous = redis.call('HGET', KEYS[1], 'sid')
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'startedAt', ARGV[1], 'expiresAt', ARGV[2], 'exhausted', 'false')
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return previous
            """;

    /** KEYS[1] the hash; ARGV field, value. Sets the field only while the verification is open. */
    private static final String SET_OPEN_FIELD = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            end
            return 0
            """;

    private final RedisDataSource redis;
    private final HashCommands<String, String, String> hashes;

    @Inject
    RedisVerificationRecords(RedisDataSource redis) {
        this.redis = redis;
        this.hashes = redis.hash(String.class);
    }

    @Override
    public String replace(String phone, Instant startedAt, Instant expiresAt) {
        Response previous = redis.execute("EVAL", REPLACE, "1", PREFIX + phone,
                Long.toString(startedAt.toEpochMilli()), Long.toString(expiresAt.toEpochMilli()),
                Long.toString(Math.max(1, Duration.between(startedAt, expiresAt).getSeconds())));
        String sid = previous == null ? null : previous.toString();
        return sid == null || sid.isEmpty() ? null : sid;
    }

    @Override
    public void rememberSid(String phone, String sid) {
        setOpenField(phone, "sid", sid);
    }

    @Override
    public Open read(String phone) {
        Map<String, String> stored = hashes.hgetall(PREFIX + phone);
        if (stored == null || stored.get("startedAt") == null || stored.get("expiresAt") == null) {
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
        hashes.hdel(PREFIX + phone, "sid");
    }

    @Override
    public void exhaust(String phone) {
        setOpenField(phone, "exhausted", "true");
    }

    @Override
    public void clear(String phone) {
        redis.key().del(PREFIX + phone);
    }

    private void setOpenField(String phone, String field, String value) {
        redis.execute("EVAL", SET_OPEN_FIELD, "1", PREFIX + phone, field, value);
    }
}
