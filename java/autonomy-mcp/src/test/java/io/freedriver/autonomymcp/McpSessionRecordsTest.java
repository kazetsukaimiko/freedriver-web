package io.freedriver.autonomymcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpSessionRecordsTest {

    @Test
    void a_session_stored_on_one_autonomy_process_is_accepted_on_another() {
        MapKeys keys = new MapKeys();
        McpSessionRecords records = new RedisSessionRecords(keys);
        McpSessions first = new McpSessions(records);
        McpSessions second = new McpSessions(records);

        String id = first.open("alice");

        assertEquals(McpSessions.Status.MATCH, second.check(id, "alice"));
        assertEquals(Duration.ofHours(24).toSeconds(), keys.ttl(RedisSessionRecords.PREFIX + id));
        assertEquals(McpSessions.Status.OTHER_SUBJECT, second.check(id, "bob"));
        first.close(id, "bob");
        assertEquals(McpSessions.Status.MATCH, second.check(id, "alice"));
        second.close(id, "alice");
        assertEquals(McpSessions.Status.UNKNOWN, first.check(id, "alice"));
    }

    /** In-memory stand-in for one Redis. Two records share it. */
    private static final class MapKeys implements RedisSessionRecords.Keys {

        private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> ttls = new ConcurrentHashMap<>();

        @Override
        public void set(String key, String value, long ttlSeconds) {
            values.put(key, value);
            ttls.put(key, ttlSeconds);
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public boolean deleteIfValue(String key, String expected) {
            boolean removed = values.remove(key, expected);
            if (removed) {
                ttls.remove(key);
            }
            return removed;
        }

        long ttl(String key) {
            Long ttl = ttls.get(key);
            return ttl == null ? -1 : ttl;
        }
    }
}
