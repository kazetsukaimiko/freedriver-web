package io.freedriver.sms.otp;

import io.freedriver.sms.support.MutableClock;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two verification stores on the Dev Services Redis, as two sms replicas would be. */
@QuarkusTest
class VerificationRecordsTest {

    private static final String PHONE = "+15555550100";
    private static final String SID = "VE" + "ab".repeat(16);
    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");

    @Inject
    RedisDataSource redis;

    @BeforeEach
    void flush() {
        redis.flushall();
    }

    @Test
    void a_code_issued_on_one_sms_process_is_valid_on_another_until_it_expires() {
        VerificationLedger first = new VerificationLedger(new RedisVerificationRecords(redis));
        VerificationLedger second = new VerificationLedger(new RedisVerificationRecords(redis));
        MutableClock clock = new MutableClock(START);
        first.setClock(clock);
        second.setClock(clock);

        assertNull(first.replace(PHONE));
        first.rememberSid(PHONE, SID);

        assertFalse(second.expired(PHONE));
        assertNull(second.peekExpiredSid(PHONE));
        assertTtlIsFiveMinutes();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertTrue(second.expired(PHONE));
        assertEquals(SID, second.peekExpiredSid(PHONE));
    }

    @Test
    void an_open_code_is_a_redis_hash_that_expires_five_minutes_after_the_send() {
        VerificationRecords records = new RedisVerificationRecords(redis);
        Instant expiresAt = START.plus(VerificationLedger.TTL);

        assertNull(records.replace(PHONE, START, expiresAt));
        records.rememberSid(PHONE, SID);

        assertTtlIsFiveMinutes();
        VerificationRecords.Open read = records.read(PHONE);
        assertEquals(expiresAt, read.expiresAt());
        assertEquals(SID, read.sid());
        assertFalse(read.exhausted());
        assertEquals(SID, records.replace(PHONE, START, expiresAt), "a new send returns the sid to cancel");
        assertNull(records.read(PHONE).sid());
    }

    @Test
    void two_replicas_interleave_remember_sid_and_exhaust_and_both_fields_survive() throws Exception {
        VerificationRecords first = new RedisVerificationRecords(redis);
        VerificationRecords second = new RedisVerificationRecords(redis);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                String phone = "+1555555" + String.format("%04d", 200 + round);
                first.replace(phone, START, START.plus(VerificationLedger.TTL));
                CountDownLatch go = new CountDownLatch(1);
                Future<?> sid = pool.submit(() -> {
                    go.await();
                    first.rememberSid(phone, SID);
                    return null;
                });
                Future<?> exhaust = pool.submit(() -> {
                    go.await();
                    second.exhaust(phone);
                    return null;
                });
                go.countDown();
                sid.get(5, TimeUnit.SECONDS);
                exhaust.get(5, TimeUnit.SECONDS);

                VerificationRecords.Open read = first.read(phone);
                assertEquals(SID, read.sid(), "round " + round);
                assertTrue(read.exhausted(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void a_field_write_after_expiry_does_not_recreate_the_code() {
        VerificationRecords records = new RedisVerificationRecords(redis);
        records.replace(PHONE, START, START.plus(VerificationLedger.TTL));
        records.clear(PHONE);

        records.rememberSid(PHONE, SID);
        records.exhaust(PHONE);

        assertNull(records.read(PHONE));
        assertFalse(redis.key().exists(RedisVerificationRecords.PREFIX + PHONE));
    }

    private void assertTtlIsFiveMinutes() {
        long ttl = redis.key().ttl(RedisVerificationRecords.PREFIX + PHONE);
        assertTrue(ttl > Duration.ofMinutes(5).toSeconds() - 5 && ttl <= Duration.ofMinutes(5).toSeconds(), "ttl " + ttl);
    }
}
