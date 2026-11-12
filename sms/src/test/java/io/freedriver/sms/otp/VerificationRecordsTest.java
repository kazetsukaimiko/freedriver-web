package io.freedriver.sms.otp;

import io.freedriver.sms.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationRecordsTest {

    private static final String PHONE = "+15555550100";
    private static final String SID = "VE" + "ab".repeat(16);
    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void a_code_issued_on_one_sms_process_is_valid_on_another_until_it_expires() {
        MapHashes hashes = new MapHashes();
        VerificationRecords shared = new RedisVerificationRecords(hashes);
        VerificationLedger first = new VerificationLedger(shared);
        VerificationLedger second = new VerificationLedger(shared);
        MutableClock clock = new MutableClock(START);
        first.setClock(clock);
        second.setClock(clock);

        assertNull(first.replace(PHONE));
        first.rememberSid(PHONE, SID);

        assertFalse(second.expired(PHONE));
        assertNull(second.peekExpiredSid(PHONE));
        assertEquals(Duration.ofMinutes(5).toSeconds(), hashes.ttl(RedisVerificationRecords.PREFIX + PHONE));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertTrue(second.expired(PHONE));
        assertEquals(SID, second.peekExpiredSid(PHONE));
    }

    @Test
    void an_open_code_is_a_redis_key_that_expires_five_minutes_after_the_send() {
        MapHashes hashes = new MapHashes();
        VerificationRecords records = new RedisVerificationRecords(hashes);
        Instant expiresAt = START.plus(VerificationLedger.TTL);

        assertNull(records.replace(PHONE, START, expiresAt));
        records.rememberSid(PHONE, SID);

        assertEquals(Duration.ofMinutes(5).toSeconds(), hashes.ttl(RedisVerificationRecords.PREFIX + PHONE));
        VerificationRecords.Open read = records.read(PHONE);
        assertEquals(expiresAt, read.expiresAt());
        assertEquals(SID, read.sid());
        assertFalse(read.exhausted());
    }

    /** In-memory stand-in for one Redis. Two records share it. */
    private static final class MapHashes implements RedisVerificationRecords.Hashes {

        private final ConcurrentHashMap<String, Map<String, String>> values = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> ttls = new ConcurrentHashMap<>();

        @Override
        public String replace(String key, Map<String, String> fields, long ttlSeconds) {
            Map<String, String> previous = values.get(key);
            String sid = previous == null ? null : previous.get("sid");
            write(key, fields, ttlSeconds);
            return sid == null || sid.isEmpty() ? null : sid;
        }

        @Override
        public Map<String, String> read(String key) {
            Map<String, String> stored = values.get(key);
            return stored == null ? Map.of() : Map.copyOf(stored);
        }

        @Override
        public void write(String key, Map<String, String> fields, long ttlSeconds) {
            values.put(key, new LinkedHashMap<>(fields));
            ttls.put(key, ttlSeconds);
        }

        @Override
        public void delete(String key) {
            values.remove(key);
            ttls.remove(key);
        }

        @Override
        public void deleteAll() {
            values.clear();
            ttls.clear();
        }

        long ttl(String key) {
            Long ttl = ttls.get(key);
            return ttl == null ? -1 : ttl;
        }
    }
}
