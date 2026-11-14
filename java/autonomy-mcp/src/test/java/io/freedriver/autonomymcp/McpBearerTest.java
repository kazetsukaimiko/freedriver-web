package io.freedriver.autonomymcp;

import io.freedriver.mcp.McpEndpoint;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(McpBearerTest.OidcOn.class)
@QuarkusTestResource(value = McpOidc.class, restrictToAnnotatedClass = true)
class McpBearerTest {

    static final String CLIENT_ID = "freedriver-grok-mcp";

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> conf = new HashMap<>();
            conf.put("quarkus.oidc.enabled", "true");
            conf.put("quarkus.oidc.application-type", "service");
            conf.put("quarkus.oidc.client-id", McpBearerTest.CLIENT_ID);
            conf.put("quarkus.oidc.token.issuer", "https://server.example.com");
            conf.put("quarkus.oidc.token.audience", McpResourceTest.RESOURCE);
            conf.put("quarkus.http.test-port", "0");
            return conf;
        }
    }

    @Test
    void good_token_initialize_is_200_without_set_cookie() {
        String token = token("mcp-sub-good", Set.of("mcp-user"), McpResourceTest.RESOURCE, McpBearerTest.CLIENT_ID, null, null);
        given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + McpResourceTest.INIT_PARAMS + "}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .header("Set-Cookie", nullValue())
                .header("Location", nullValue());
    }

    @Test
    void expired_token_is_401_for_every_tool() {
        String token = token(
                "mcp-sub-exp",
                Set.of("mcp-user", "dashboard", "mcp-debug"),
                McpResourceTest.RESOURCE,
                McpBearerTest.CLIENT_ID,
                Instant.now().minusSeconds(60),
                null);
        for (String body : new String[] {
            call("list_appliances"),
            call("home_status"),
            call("debug"),
            switchBody()
        }) {
            given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                    .redirects().follow(false)
                    .contentType("application/json")
                    .body(body)
                    .post("/autonomy/mcp")
                    .then()
                    .statusCode(401)
                    .header("Location", nullValue());
        }
    }

    @Test
    void wrong_audience_is_401() {
        String token = token("mcp-sub-aud", Set.of("mcp-user"), "freedriver-api", McpBearerTest.CLIENT_ID, null, null);
        given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .redirects().follow(false)
                .contentType("application/json")
                .body(call("list_appliances"))
                .post("/autonomy/mcp")
                .then()
                .statusCode(401)
                .header("Location", nullValue());
    }

    @Test
    void dcr_client_reaches_tools_list() {
        String token = token(
                "mcp-sub-dcr",
                Set.of("mcp-user"),
                McpResourceTest.RESOURCE,
                "9d0238fd-47cb-4e86-bf03-ac4c16f6107c",
                null,
                null);
        String session = given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + McpResourceTest.INIT_PARAMS + "}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .extract()
                .header(McpEndpoint.SESSION_HEADER);
        given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .header(McpEndpoint.SESSION_HEADER, session)
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(200)
                .body("result.tools.name", hasItem("list_appliances"))
                .body("result.tools.name", hasItem("switch_appliance"));
    }

    @Test
    void missing_mcp_user_is_403() {
        String token = token("mcp-sub-role", Set.of("portal-admin"), McpResourceTest.RESOURCE, McpBearerTest.CLIENT_ID, null, null);
        given().header("Authorization", "Bearer " + token)
                .accept("application/json, text/event-stream")
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + McpResourceTest.INIT_PARAMS + "}")
                .post("/autonomy/mcp")
                .then()
                .statusCode(403);
    }

    private static String call(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}";
    }

    private static String switchBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"switch_appliance\",\"arguments\":{"
                + "\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                + "\"applianceName\":\"fridge\",\"on\":false}}}";
    }

    private static String token(
            String sub, Set<String> groups, String aud, String azp, Instant expires, String issuer) {
        var builder = Jwt.preferredUserName("ada")
                .groups(groups)
                .issuer(issuer == null ? "https://server.example.com" : issuer)
                .subject(sub)
                .claim("typ", "Bearer");
        if (azp != null) {
            builder = builder.claim("azp", azp);
        }
        if (aud != null) {
            builder = builder.audience(aud);
        }
        if (expires != null) {
            builder = builder.expiresAt(expires);
        }
        return builder.jws().keyId("1").sign("privateKey.jwk");
    }
}
