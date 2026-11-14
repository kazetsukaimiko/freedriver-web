package io.freedriver.sms.security;

import io.freedriver.ratelimit.RedisHitWindow;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sliding windows on the Dev Services Redis, shared by two instances as two sms replicas would share them. */
@QuarkusTest
class RedisHitWindowTest {

    @Inject
    RedisDataSource redis;

    @BeforeEach
    void flush() {
        redis.flushall();
    }

    @Test
    void two_instances_share_one_window() {
        RedisHitWindow firstWindow = new RedisHitWindow(redis, "sms");
        RedisHitWindow secondWindow = new RedisHitWindow(redis, "sms");
        PhoneRateLimiter first = new PhoneRateLimiter(Duration.ofMinutes(15), 2, 2, firstWindow);
        PhoneRateLimiter second = new PhoneRateLimiter(Duration.ofMinutes(15), 2, 2, secondWindow);
        String phone = "+15555550100";

        assertTrue(first.tryAcquireSend(phone));
        assertTrue(second.tryAcquireSend(phone));
        assertFalse(first.tryAcquireSend(phone));
        assertTrue(second.tryAcquireSend("+15555550101"));
        assertEquals(2, firstWindow.count("send:" + phone, Duration.ofMinutes(15).toMillis()));
        assertEquals(2, secondWindow.count("send:" + phone, Duration.ofMinutes(15).toMillis()));
    }

    @Test
    void a_hit_given_back_frees_its_slot_and_delete_empties_the_key() {
        RedisHitWindow window = new RedisHitWindow(redis, "sms");
        long minute = Duration.ofMinutes(1).toMillis();

        String first = window.tryAdd("k", minute, 2);
        assertNotNull(window.tryAdd("k", minute, 2));
        assertNull(window.tryAdd("k", minute, 2));
        window.remove("k", first);
        assertEquals(1, window.count("k", minute));
        assertNotNull(window.add("k", minute), "add records a hit past the limit");
        assertNotNull(window.add("k", minute));
        assertEquals(3, window.count("k", minute));

        window.delete("k");
        assertEquals(0, window.count("k", minute));
        assertFalse(redis.key().exists("freedriver:rate:sms:k"));
    }

    @Test
    void hits_leave_the_window_by_redis_time_and_the_key_expires() throws Exception {
        RedisHitWindow window = new RedisHitWindow(redis, "sms");
        long windowMs = 300;

        assertNotNull(window.tryAdd("short", windowMs, 1));
        assertNull(window.tryAdd("short", windowMs, 1));
        assertTrue(redis.key().pttl("freedriver:rate:sms:short") > 0);

        Thread.sleep(windowMs + 100);
        assertEquals(0, window.count("short", windowMs));
        assertNotNull(window.tryAdd("short", windowMs, 1));
    }
}
