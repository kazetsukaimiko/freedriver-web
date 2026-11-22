package io.freedriver.autonomymcp;

import io.freedriver.dashboard.api.PendingCapHeader;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DashboardResponses#toThrowable} reads the pending-cap header and leaves the body unread. */
class DashboardResponsesMapperTest {

    @Test
    void a_full_header_marks_the_pending_cap() {
        DashboardRefused refused = map(Response.status(429).header(PendingCapHeader.NAME, PendingCapHeader.FULL));
        assertEquals(429, refused.status());
        assertTrue(refused.pendingCap());
    }

    @Test
    void a_missing_header_leaves_the_cap_unmarked() {
        DashboardRefused refused = map(Response.status(429));
        assertEquals(429, refused.status());
        assertFalse(refused.pendingCap());
    }

    @Test
    void another_header_value_leaves_the_cap_unmarked() {
        DashboardRefused refused = map(Response.status(403).header(PendingCapHeader.NAME, "other"));
        assertEquals(403, refused.status());
        assertFalse(refused.pendingCap());
    }

    private static DashboardRefused map(Response.ResponseBuilder builder) {
        try (Response response = builder.build()) {
            return new DashboardResponses().toThrowable(response);
        }
    }
}
