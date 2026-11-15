package io.freedriver.autonomymcp;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(McpDisabledTest.Off.class)
class McpDisabledTest {

    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.mcp.enabled", "false",
                    "%test.freedriver.mcp.enabled", "false",
                    "quarkus.http.test-port", "0");
        }
    }

    @Test
    void anonymous_ready_is_open() {
        given().get("/q/health/live").then().statusCode(200);
        given().get("/q/health/ready").then().statusCode(anyOf(is(200), is(503)));
    }

    @Test
    void mcp_is_404_when_off() {
        given().redirects().follow(false)
                .get("/autonomy/mcp")
                .then()
                .statusCode(404)
                .header("Location", nullValue());
        given().redirects().follow(false)
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(404)
                .header("Location", nullValue());
    }
}
