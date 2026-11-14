package io.freedriver.dispatcher;

import io.freedriver.mcp.McpEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The answers the MCP library gives where the hand checks answered before. Each case names the
 * old answer:
 * <ul>
 *     <li>an unknown argument: before, JSON-RPC -32602 "invalid params"; now ignored, the tool runs;</li>
 *     <li>a fraction for an integer: before and now -32602; the message now names the argument;</li>
 *     <li>a number for an enum: before and now -32602;</li>
 *     <li>an unknown enum value: before, a tool error result; now -32602;</li>
 *     <li>a wrong role: before, a tool error result "wrong role"; now JSON-RPC -32005, the security error.</li>
 * </ul>
 */
@QuarkusTest
class McpPinnedAnswersTest {

    @Test
    @TestSecurity(user = "pin-unknown-argument", roles = "dispatcher")
    void an_unknown_argument_is_ignored() {
        call(initialize(), tool("list_agents", "{\"extra\":1}"))
                .statusCode(200)
                .body("result.isError", is(false));
    }

    @Test
    @TestSecurity(user = "pin-fraction", roles = "dispatcher")
    void a_fraction_for_an_integer_is_invalid_params() {
        call(initialize(), tool("openbot_await_turn", "{\"jobId\":\"" + UUID.randomUUID() + "\",\"delaySeconds\":1.5}"))
                .statusCode(200)
                .body("error.code", is(-32602))
                .body("error.message", is("Invalid argument [delaySeconds] - unable to convert value"));
    }

    @Test
    @TestSecurity(user = "pin-enum", roles = "dispatcher")
    void a_number_or_an_unknown_value_for_an_enum_is_invalid_params() {
        String session = initialize();
        for (String onBusy : new String[] {"1", "\"interrupt\""}) {
            call(session, tool("openbot_send_prompt", "{\"server\":\"none.local\",\"agentName\":\"yuni\","
                    + "\"prompt\":\"nope\",\"channel\":\"TEXT\",\"on_busy\":" + onBusy + "}"))
                    .statusCode(200)
                    .body("error.code", is(-32602))
                    .body("error.message", is("Invalid argument [on_busy] - unable to convert value"));
        }
    }

    @Test
    @TestSecurity(user = "pin-wrong-role", roles = "dispatcher")
    void a_wrong_role_is_the_security_error() {
        call(initialize(), tool("finish_job", "{\"assignmentId\":\"" + UUID.randomUUID() + "\",\"succeeded\":true}"))
                .statusCode(200)
                .body("error.code", is(-32005))
                .body("error.message", is("io.quarkus.security.ForbiddenException"));
    }

    private static String tool(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}}";
    }

    private static String initialize() {
        return given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}")
                .post("/mcp")
                .then()
                .statusCode(200)
                .extract()
                .header(McpEndpoint.SESSION_HEADER);
    }

    private static ValidatableResponse call(String sessionId, String body) {
        return given()
                .contentType("application/json")
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, sessionId)
                .body(body)
                .post("/mcp")
                .then();
    }
}
