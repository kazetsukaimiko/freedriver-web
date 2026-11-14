package io.freedriver.dispatcher;

import com.mongodb.client.model.Sorts;
import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.ne;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every dispatcher query uses an index, and the store bean refuses input over the model limits. */
@QuarkusTest
class JobIndexesTest {

    @Inject
    AssignmentRepository assignments;

    @Inject
    DeviceRepository devices;

    @Inject
    SubmitLocks locks;

    @Inject
    JobStore store;

    @Test
    void each_query_is_an_index_scan() {
        var jobs = assignments.mongoCollection();
        assertIndexed(jobs.find(and(eq("deviceId", "pc-a"), eq("status", AssignmentState.QUEUED.name())))
                .sort(Sorts.ascending("ordinal")).explain(), "deviceId_1_status_1_ordinal_1");
        assertIndexed(jobs.find(eq("jobId", "j")).sort(Sorts.ascending("ordinal")).explain(), "jobId_1");
        assertIndexed(jobs.find(ne("jobId", null)).sort(Sorts.descending("createdAt", "_id")).explain(),
                "createdAt_-1__id_-1");
        assertIndexed(jobs.find(eq("dispatcherSubject", "grok")).sort(Sorts.ascending("createdAt")).explain(),
                "dispatcherSubject_1_createdAt_-1");
        assertIndexed(jobs.aggregate(List.of(new Document("$match", new Document("dispatcherSubject", "grok")
                .append("jobId", new Document("$ne", null))))).explain(), "dispatcherSubject_1_createdAt_-1");
        assertIndexed(devices.mongoCollection().find(and(eq("hostname", "a.local"), eq("agents", "yuni"))).explain(),
                "hostname_1");
        Document ttl = locks.mongoCollection().listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(index -> "expiresAt_1".equals(index.getString("name"))).findFirst().orElseThrow();
        assertTrue(ttl.get("expireAfterSeconds") != null, ttl.toJson());
    }

    @Test
    void the_bean_refuses_input_over_the_model_limits() {
        assertThrows(ConstraintViolationException.class, () -> store.submitNow("grok",
                new SubmitJobRequest(List.of("pc-a"), "yuni", "x".repeat(16001), Channel.TEXT)));
        assertThrows(ConstraintViolationException.class, () -> store.submit("grok",
                new SubmitJobRequest(List.of("pc-a"), "a".repeat(65), "hello", Channel.TEXT), Duration.ZERO));
        assertThrows(ConstraintViolationException.class, () -> store.report("pc-a", "a.local",
                Collections.nCopies(33, new ReportedBot("yuni", "idle", ""))));
        assertThrows(ConstraintViolationException.class, () -> store.report("pc-a", "a.local",
                List.of(new ReportedBot("yuni", "asleep", ""))));
        assertThrows(ConstraintViolationException.class, () -> store.finish("pc-a",
                java.util.UUID.randomUUID(), true, "n".repeat(2001)));
    }

    private static void assertIndexed(Document explain, String index) {
        String plan = explain.toJson();
        assertTrue(plan.contains("IXSCAN") && plan.contains(index), index + " not used: " + plan);
    }
}
