package io.freedriver.app.schedule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisLockVendorTest {

    @Test
    void a_second_holder_waits_until_the_first_closes() throws Exception {
        MemoryCommands commands = new MemoryCommands();
        RedisLockVendor vendor = new RedisLockVendor(commands);
        AutoCloseable first = vendor.apply("ScheduledSwitchPoller", Duration.ofSeconds(45));
        assertThrows(LockUnavailable.class, () -> vendor.apply("ScheduledSwitchPoller", Duration.ofSeconds(45)));
        first.close();
        try (AutoCloseable ignored = vendor.apply("ScheduledSwitchPoller", Duration.ofSeconds(45))) {
            assertTrue(commands.values.containsKey("freedriver:lock:ScheduledSwitchPoller"));
        }
        assertFalse(commands.values.containsKey("freedriver:lock:ScheduledSwitchPoller"));
    }

    @Test
    void an_expired_hold_can_be_taken() throws Exception {
        MemoryCommands commands = new MemoryCommands();
        RedisLockVendor vendor = new RedisLockVendor(commands);
        try (AutoCloseable ignored = vendor.apply("ScheduledSwitchPoller", Duration.ofMillis(30))) {
            commands.now = 30;
            try (AutoCloseable taken = vendor.apply("ScheduledSwitchPoller", Duration.ofSeconds(45))) {
                assertTrue(commands.values.containsKey("freedriver:lock:ScheduledSwitchPoller"));
            }
        }
    }

    static final class MemoryCommands implements RedisLockVendor.Commands {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Long> expiry = new HashMap<>();
        long now;

        @Override
        public synchronized boolean tryHold(String key, String token, long ttlMillis) {
            purge();
            if (values.containsKey(key)) {
                return false;
            }
            values.put(key, token);
            expiry.put(key, now + ttlMillis);
            return true;
        }

        @Override
        public synchronized void release(String key, String token) {
            purge();
            if (!token.equals(values.get(key))) {
                return;
            }
            values.remove(key);
            expiry.remove(key);
        }

        private void purge() {
            values.entrySet().removeIf(entry -> expiry.getOrDefault(entry.getKey(), 0L) <= now);
        }
    }
}
