package io.freedriver.app.invites;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code invites} collection on the Dev Services Mongo container. */
@QuarkusTest
@TestProfile(InviteProfile.class)
class InviteStoreTest {

    private static final int ROUNDS = 10;

    @Inject
    InviteStore store;

    @TempDir
    Path archive;

    @BeforeEach
    void empty() {
        store.deleteAll();
    }

    @Test
    void two_store_instances_race_use_on_one_code_and_one_wins() throws Exception {
        InviteStore first = new InviteStore();
        InviteStore second = new InviteStore();
        first.clock = Clock.systemUTC();
        second.clock = Clock.systemUTC();
        for (int round = 0; round < ROUNDS; round++) {
            String code = store.create("Mom").code();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<InviteStore.UseResult> one = pool.submit(() -> {
                    go.await();
                    return first.use(code);
                });
                Future<InviteStore.UseResult> two = pool.submit(() -> {
                    go.await();
                    return second.use(code);
                });
                go.countDown();
                List<InviteStore.UseResult> results = List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(InviteStore.UseResult.USED::equals).count(), results::toString);
            } finally {
                pool.shutdownNow();
            }
            assertFalse(store.state(code).orElseThrow().open());
        }
    }

    @Test
    void the_code_is_not_stored_only_its_hash() {
        InviteStore.Created created = store.create("Mom");

        Document row = store.mongoCollection().withDocumentClass(Document.class)
                .find(eq("_id", created.invite().id().toString())).first();
        assertFalse(row.toJson().contains(created.code()));
        assertEquals(64, row.getString("codeHash").length());
    }

    @Test
    void the_archive_imports_under_invite_ids_and_a_second_import_inserts_nothing() throws Exception {
        UUID used = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        Path file = archive.resolve(InviteStore.FILE);
        Files.writeString(file, "["
                + "{\"id\":\"" + used + "\",\"name\":\"Mom\",\"codeHash\":\"" + "a".repeat(64) + "\","
                + "\"createdAt\":\"2026-11-01T10:00:00Z\",\"expiresAt\":\"2026-11-04T10:00:00Z\","
                + "\"usedAt\":\"2026-11-02T09:00:00Z\",\"cancelledAt\":null,\"submissions\":1,\"codesSent\":2,"
                + "\"pendingPhone\":\"+15555550123\"},"
                + "{\"id\":\"" + older + "\",\"name\":\"Dad\",\"codeHash\":\"" + "b".repeat(64) + "\","
                + "\"createdAt\":\"2026-10-01T10:00:00Z\",\"expiresAt\":\"2026-10-04T10:00:00Z\"}"
                + "]", StandardCharsets.UTF_8);

        assertEquals(2, store.importFile(file));
        assertEquals(0, store.importFile(file));

        Invite imported = store.findById(used.toString());
        assertEquals("Mom", imported.name);
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), imported.usedAt);
        assertEquals(2, imported.codesSent);
        assertEquals("+15555550123", imported.pendingPhone);
        Invite bare = store.findById(older.toString());
        assertNull(bare.submissions);
        assertEquals(2, store.count());
        assertTrue(Files.exists(file), "the archive file stays in place");
    }

    @Test
    void an_import_that_stopped_after_the_first_row_resumes_on_the_next_start() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        Path partial = archive.resolve("partial.json");
        Files.writeString(partial, "[" + archived(first, "Mom") + "]", StandardCharsets.UTF_8);
        assertEquals(1, store.importFile(partial));
        Path file = archive.resolve(InviteStore.FILE);
        Files.writeString(file, "[" + archived(first, "Mom") + "," + archived(second, "Dad") + ","
                + archived(last, "Gran") + "]", StandardCharsets.UTF_8);

        assertEquals(2, store.importArchive(file));
        assertEquals(0, store.importArchive(file), "a finished import is not run again");

        assertEquals("Mom", store.findById(first.toString()).name);
        assertEquals("Dad", store.findById(second.toString()).name);
        assertEquals("Gran", store.findById(last.toString()).name);
        assertEquals(3, store.count());
    }

    private static String archived(UUID id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"codeHash\":\"" + (id.toString().replace("-", "") + "0".repeat(32)) + "\","
                + "\"createdAt\":\"2026-10-01T10:00:00Z\",\"expiresAt\":\"2026-10-04T10:00:00Z\"}";
    }

    @Test
    void cancel_and_the_attempt_caps_are_filtered_updates() {
        InviteStore.Created created = store.create("Sis");
        String code = created.code();

        for (int i = 0; i < 3; i++) {
            assertEquals(InviteStore.Slot.OK, store.takeSubmission(code, "+1555555010" + i));
        }
        assertEquals(InviteStore.Slot.DEAD, store.takeSubmission(code, "+15555550109"), "three submissions");
        assertEquals(InviteStore.Slot.CODES, store.takeResend(code), "three codes");

        assertTrue(store.cancel(created.invite().id()));
        assertFalse(store.cancel(created.invite().id()), "a cancelled invite is not cancelled twice");
        assertEquals(InviteStore.UseResult.REJECTED, store.use(code));
        List<InviteView> open = new ArrayList<>(store.open());
        assertTrue(open.stream().noneMatch(view -> view.id().equals(created.invite().id())));
    }
}
