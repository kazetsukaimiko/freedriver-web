package io.freedriver.dispatcher;

import io.freedriver.mcp.McpSessions;
import io.freedriver.mcp.RedisSessionRecords;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two dispatcher processes, as two record beans on the one Redis, share every session binding. */
@QuarkusTest
class McpSessionRecordsTest {

    private static final String PREFIX = "freedriver:mcp:dispatcher:";

    @Inject
    RedisDataSource redis;

    @Inject
    ReactiveRedisDataSource reactive;

    @Test
    void a_session_bound_on_one_dispatcher_is_checked_on_another() {
        McpSessions first = new McpSessions(new RedisSessionRecords(reactive, PREFIX));
        McpSessions second = new McpSessions(new RedisSessionRecords(reactive, PREFIX));
        String id = UUID.randomUUID().toString();

        first.remember(id, "alice").await().indefinitely();

        assertEquals(McpSessions.Status.MATCH, second.check(id, "alice").await().indefinitely());
        long ttl = redis.key().ttl(PREFIX + id);
        assertTrue(ttl > Duration.ofHours(23).toSeconds() && ttl <= Duration.ofHours(24).toSeconds(), "ttl " + ttl);
        assertEquals("alice", redis.value(String.class).get(PREFIX + id));
        assertEquals(McpSessions.Status.OTHER_SUBJECT, second.check(id, "bob").await().indefinitely());
        first.close(id, "bob").await().indefinitely();
        assertEquals(McpSessions.Status.MATCH, second.check(id, "alice").await().indefinitely());
        second.close(id, "alice").await().indefinitely();
        assertEquals(McpSessions.Status.UNKNOWN, first.check(id, "alice").await().indefinitely());
        assertEquals(McpSessions.Status.ABSENT, first.check(null, "alice").await().indefinitely());
    }
}
