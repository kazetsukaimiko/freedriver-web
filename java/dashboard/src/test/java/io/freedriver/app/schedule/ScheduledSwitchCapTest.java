package io.freedriver.app.schedule;

import io.freedriver.app.security.CsrfFilter;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pending cap on {@code POST /api/mcp/scheduled-switches}, and the held row the poller skips.
 */
@QuarkusTest
class ScheduledSwitchCapTest {

    private static final String SUBJECT_A = "subject-a";
    private static final String SUBJECT_B = "subject-b";
    private static final UUID HOUSE_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID HOUSE_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    @Inject
    ScheduledSwitchRepository rows;

    @Inject
    ScheduledSwitchesConfig config;

    @Inject
    TestIdentityAssociation identities;

    @BeforeEach
    void empty() {
        rows.deleteAll();
    }

    @Test
    @TestSecurity(user = "ada", roles = "dashboard", attributes = {
            @io.quarkus.test.security.SecurityAttribute(key = "sub", value = SUBJECT_A)
    })
    void one_subject_shares_the_cap_across_houses_and_leased_rows() {
        assertFalse(config.poll());
        int cap = config.maxPending();
        Instant later = Instant.now().plusSeconds(120);
        assertEquals(202, post(HOUSE_2, "routed", later).statusCode());
        int filled = 1;
        ScheduledSwitch leased = stored(SUBJECT_A, HOUSE_1, "leased", later);
        leased.claimedUntil = later;
        rows.persist(leased);
        filled++;
        int n = 0;
        while (filled < cap) {
            UUID house = n % 2 == 0 ? HOUSE_1 : HOUSE_2;
            rows.persist(stored(SUBJECT_A, house, "a-" + n, later));
            filled++;
            n++;
        }
        assertEquals(cap, rows.countBySubject(SUBJECT_A));

        Response refused = post(HOUSE_2, "one-over", later);
        assertEquals(429, refused.statusCode());
        assertEquals("", refused.body().asString());
        assertEquals(cap, rows.countBySubject(SUBJECT_A));
        assertTrue(rows.list("applianceName", "one-over").isEmpty());

        signIn("bob", SUBJECT_B);
        assertEquals(202, post(HOUSE_1, "b-ok", later).statusCode());
        assertEquals(1, rows.countBySubject(SUBJECT_B));
        assertEquals(cap, rows.countBySubject(SUBJECT_A));

        signIn("ada", SUBJECT_A);
        List<String> seeded = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ScheduledSwitch extra = stored(SUBJECT_A, i == 0 ? HOUSE_1 : HOUSE_2, "extra-" + i, later);
            if (i == 0) {
                extra.claimedUntil = later;
            }
            rows.persist(extra);
            seeded.add(extra.id);
        }
        long above = rows.countBySubject(SUBJECT_A);
        assertTrue(above > cap);
        Response stillRefused = post(HOUSE_1, "still-over", later);
        assertEquals(429, stillRefused.statusCode());
        assertEquals("", stillRefused.body().asString());
        assertEquals(above, rows.countBySubject(SUBJECT_A));
        for (String id : seeded) {
            assertEquals(id, rows.findById(id).id);
        }

        while (rows.countBySubject(SUBJECT_A) > cap) {
            rows.deleteById(seeded.removeFirst());
        }
        assertEquals(cap, rows.countBySubject(SUBJECT_A));
        assertEquals(429, post(HOUSE_2, "at-cap", later).statusCode());
        assertEquals(cap, rows.countBySubject(SUBJECT_A));

        rows.deleteById(rows.list("subject", SUBJECT_A).getFirst().id);
        assertEquals(cap - 1, rows.countBySubject(SUBJECT_A));
        assertEquals(202, post(HOUSE_1, "under", later).statusCode());
        assertEquals(cap, rows.countBySubject(SUBJECT_A));
    }

    @Test
    @TestSecurity(user = "ada", roles = "dashboard", attributes = {
            @io.quarkus.test.security.SecurityAttribute(key = "sub", value = SUBJECT_A)
    })
    void rows_already_over_the_cap_survive_a_refusal_and_still_fire() {
        int cap = config.maxPending();
        Instant due = Instant.now();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < cap + 2; i++) {
            ScheduledSwitch row = stored(SUBJECT_A, i % 2 == 0 ? HOUSE_1 : HOUSE_2, "fire-" + i, due);
            if (i == 0) {
                row.claimedUntil = due.minusSeconds(5);
                row.attempts = 1;
            }
            rows.persist(row);
            names.add(row.applianceName);
        }
        ScheduledSwitch other = stored(SUBJECT_B, HOUSE_1, "b-later", due.plusSeconds(3600));
        rows.persist(other);
        long before = rows.countBySubject(SUBJECT_A);

        Response refused = post(HOUSE_1, "not-stored", due);
        assertEquals(429, refused.statusCode());
        assertEquals("", refused.body().asString());
        assertEquals(before, rows.countBySubject(SUBJECT_A));
        assertTrue(rows.list("applianceName", "not-stored").isEmpty());

        ScheduledSwitchPollerTest.FakeAppliances appliances = new ScheduledSwitchPollerTest.FakeAppliances(0);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(
                rows, appliances, new ScheduledSwitchPollerTest.RecordingAudit(),
                new ScheduledSwitchPollerTest.MutableClock(Instant.now()));
        int guard = 0;
        while (rows.countBySubject(SUBJECT_A) > 0 && guard < 20) {
            poller.pass();
            guard++;
        }
        assertEquals(0, rows.countBySubject(SUBJECT_A));
        assertEquals(0, runs(appliances, "not-stored"));
        for (String name : names) {
            assertEquals(1, runs(appliances, name), name);
        }
        assertEquals(other.id, rows.findById(other.id).id);

        assertEquals(202, post(HOUSE_2, "slot-open", due.plusSeconds(30)).statusCode());
        assertEquals(1, rows.countBySubject(SUBJECT_A));
    }

    @Test
    @TestSecurity(user = "ada", roles = "dashboard", attributes = {
            @io.quarkus.test.security.SecurityAttribute(key = "sub", value = SUBJECT_A)
    })
    void a_request_the_rules_reject_stores_nothing_even_at_the_cap() {
        int cap = config.maxPending();
        Instant later = Instant.now().plusSeconds(60);
        for (int i = 0; i < cap; i++) {
            rows.persist(stored(SUBJECT_A, HOUSE_1, "full-" + i, later));
        }
        long before = rows.count();

        assertEquals(400, post(HOUSE_1, " ", later).statusCode());
        assertEquals(400, post(HOUSE_1, "too-far", Instant.now().plusSeconds(90_000)).statusCode());
        assertEquals(400, post(HOUSE_1, "too-old", Instant.now().minusSeconds(30)).statusCode());
        assertEquals(before, rows.count());

        signIn("only-principal", null);
        assertEquals(400, post(HOUSE_1, "no-subject", later).statusCode());
        assertEquals(before, rows.count());
        assertEquals(cap, rows.countBySubject(SUBJECT_A));
    }

    @Test
    @TestSecurity(user = "ada", roles = "dashboard", attributes = {
            @io.quarkus.test.security.SecurityAttribute(key = "sub", value = SUBJECT_A)
    })
    void concurrent_stores_at_one_below_the_cap_never_pass_it() throws Exception {
        int cap = config.maxPending();
        Instant later = Instant.now().plusSeconds(90);
        for (int i = 0; i < cap - 1; i++) {
            assertEquals(202, post(HOUSE_1, "seed-" + i, later).statusCode());
        }
        assertEquals(cap - 1, rows.countBySubject(SUBJECT_A));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> races = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int n = i;
                races.add(pool.submit(() -> post(HOUSE_2, "race-" + n, later).statusCode()));
            }
            for (Future<Integer> race : races) {
                int status = race.get(30, TimeUnit.SECONDS);
                assertTrue(status == 202 || status == 429, Integer.toString(status));
            }
        } finally {
            pool.shutdownNow();
        }
        long left = rows.countBySubject(SUBJECT_A);
        assertTrue(left <= cap, Long.toString(left));
        assertTrue(left >= cap - 1, Long.toString(left));
    }

    @Test
    void a_held_row_is_not_claimed_until_release() {
        Instant due = Instant.parse("2026-11-19T12:00:00Z");
        ScheduledSwitch row = stored(SUBJECT_A, HOUSE_1, "held", due);
        rows.storeHeld(row, due);
        ScheduledSwitch stored = rows.findById(row.id);
        assertEquals(due.plus(ScheduledSwitchPoller.LEASE), stored.claimedUntil);
        assertEquals(0, stored.attempts);

        ScheduledSwitchPollerTest.FakeAppliances appliances = new ScheduledSwitchPollerTest.FakeAppliances(0);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(
                rows, appliances, new ScheduledSwitchPollerTest.RecordingAudit(),
                new ScheduledSwitchPollerTest.MutableClock(due));
        assertEquals(0, poller.pass());
        assertEquals(0, runs(appliances, "held"));
        assertEquals(row.id, rows.findById(row.id).id);

        rows.release(row.id);
        assertNull(rows.findById(row.id).claimedUntil);
        assertEquals(1, poller.pass());
        assertEquals(1, runs(appliances, "held"));
        assertEquals(0, poller.pass());
    }

    @Test
    @TestSecurity(user = "ada", roles = "dashboard", attributes = {
            @io.quarkus.test.security.SecurityAttribute(key = "sub", value = SUBJECT_A)
    })
    void a_refused_store_due_now_leaves_no_document_and_no_command() {
        int cap = config.maxPending();
        Instant later = Instant.now().plusSeconds(60);
        for (int i = 0; i < cap; i++) {
            rows.persist(stored(SUBJECT_A, HOUSE_1, "parked-" + i, later));
        }
        Instant due = Instant.now();
        Response refused = post(HOUSE_1, "refused-now", due);
        assertEquals(429, refused.statusCode());
        assertTrue(rows.list("applianceName", "refused-now").isEmpty());

        ScheduledSwitchPollerTest.FakeAppliances appliances = new ScheduledSwitchPollerTest.FakeAppliances(0);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(
                rows, appliances, new ScheduledSwitchPollerTest.RecordingAudit(),
                new ScheduledSwitchPollerTest.MutableClock(due));
        poller.pass();
        assertEquals(0, runs(appliances, "refused-now"));
        assertEquals(cap, rows.countBySubject(SUBJECT_A));
    }

    @Test
    @TestSecurity(user = "ada", roles = "dashboard", attributes = {
            @io.quarkus.test.security.SecurityAttribute(key = "sub", value = SUBJECT_A)
    })
    void an_accepted_store_due_now_fires_without_waiting_out_the_lease() {
        Instant due = Instant.now();
        assertEquals(202, post(HOUSE_1, "due-now", due).statusCode());
        ScheduledSwitch stored = rows.list("applianceName", "due-now").getFirst();
        assertNull(stored.claimedUntil);

        ScheduledSwitchPollerTest.FakeAppliances appliances = new ScheduledSwitchPollerTest.FakeAppliances(0);
        ScheduledSwitchPoller poller = new ScheduledSwitchPoller(
                rows, appliances, new ScheduledSwitchPollerTest.RecordingAudit(),
                new ScheduledSwitchPollerTest.MutableClock(due));
        assertEquals(1, poller.pass());
        assertEquals(1, runs(appliances, "due-now"));
        assertTrue(rows.findById(stored.id) == null);
    }

    private void signIn(String user, String subject) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user))
                .addRole("dashboard");
        if (subject != null) {
            builder.addAttribute("sub", subject);
        }
        identities.setTestIdentity(builder.build());
    }

    private static Response post(UUID instanceId, String applianceName, Instant dueAt) {
        Response minted = io.restassured.RestAssured.given().when().get("/api/appliances");
        minted.then().statusCode(200);
        return io.restassured.RestAssured.given()
                .contentType(ContentType.JSON)
                .cookie(CsrfFilter.COOKIE, minted.cookie(CsrfFilter.COOKIE))
                .header(CsrfFilter.HEADER, minted.path("csrfToken"))
                .body(Map.of(
                        "instanceId", instanceId.toString(),
                        "applianceName", applianceName,
                        "on", true,
                        "dueAt", dueAt.toString()))
                .when()
                .post("/api/mcp/scheduled-switches");
    }

    private static ScheduledSwitch stored(String subject, UUID instanceId, String applianceName, Instant dueAt) {
        return new ScheduledSwitch(
                UUID.randomUUID().toString(), instanceId.toString(), applianceName, true, dueAt, subject);
    }

    private static int runs(ScheduledSwitchPollerTest.FakeAppliances appliances, String name) {
        var count = appliances.runs.get(name);
        return count == null ? 0 : count.get();
    }
}
