package io.freedriver.app.security;

import io.freedriver.app.appliances.ApplianceAudit;
import io.freedriver.app.appliances.MockAutonomy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logmanager.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class McpInternalAuthTest {

    private static final String SECRET = "test-internal-secret";

    @Inject
    MockAutonomy mock;

    @Inject
    CommandRateLimiter rateLimiter;

    @BeforeEach
    void reset() {
        mock.reset();
        rateLimiter.reset();
        mock.setConfirmCommands(true);
    }

    @Test
    void secret_and_subject_reach_mcp_ops() {
        given().header(McpInternalAuthMechanism.SECRET_HEADER, SECRET)
                .header(McpInternalAuthMechanism.SUBJECT_HEADER, "mcp-sub")
                .header(McpInternalAuthMechanism.ROLES_HEADER, "mcp-user")
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(200)
                .header("Set-Cookie", nullValue());
    }

    @Test
    void authorization_header_is_not_an_internal_credential() {
        given().header("Authorization", "Bearer grok-token")
                .header(McpInternalAuthMechanism.SECRET_HEADER, SECRET)
                .header(McpInternalAuthMechanism.SUBJECT_HEADER, "mcp-sub")
                .header(McpInternalAuthMechanism.ROLES_HEADER, "mcp-user")
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(401);
    }

    @Test
    void wrong_secret_is_401() {
        given().header(McpInternalAuthMechanism.SECRET_HEADER, "nope")
                .header(McpInternalAuthMechanism.SUBJECT_HEADER, "mcp-sub")
                .header(McpInternalAuthMechanism.ROLES_HEADER, "mcp-user")
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(401);
    }

    @Test
    void switch_skips_csrf_and_audits_the_subject() {
        String hallway = MockAutonomy.FIXTURE_NAMES.get(0);
        List<String> lines = auditLines(() -> given()
                .header(McpInternalAuthMechanism.SECRET_HEADER, SECRET)
                .header(McpInternalAuthMechanism.SUBJECT_HEADER, "mcp-switch-sub")
                .header(McpInternalAuthMechanism.ROLES_HEADER, "mcp-user,dashboard")
                .contentType("application/json")
                .body("{\"on\":true}")
                .when()
                .post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + hallway)
                .then()
                .statusCode(200)
                .body("timeout", is(false)));
        assertEquals(1, lines.size());
        String line = lines.getFirst();
        assertTrue(line.matches(
                "audit appliance when=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"
                        + " instance=" + MockAutonomy.INSTANCE_ID
                        + " appliance=" + Pattern.quote(hallway) + " on=true commandId=[0-9a-fA-F-]{36}"
                        + " result=confirmed subject=mcp-switch-sub"),
                line);
        assertFalse(line.contains(SECRET), line);
    }

    private static List<String> auditLines(Runnable action) {
        Logger logger = Logger.getLogger(ApplianceAudit.class.getName());
        List<String> lines = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String message = record.getMessage();
                if (message != null && message.startsWith("audit appliance ")) {
                    lines.add(message);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return lines;
    }
}
