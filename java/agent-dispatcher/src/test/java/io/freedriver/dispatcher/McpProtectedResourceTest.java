package io.freedriver.dispatcher;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class McpProtectedResourceTest {

    @Inject
    JobStore jobs;

    @Test
    void anonymous_get_is_the_metadata_document_and_nothing_else() {
        jobs.register("pc-meta", "meta-hostname-do-not-leak", List.of("yuni"));
        int before = jobs.jobCount();

        Map<?, ?> body = given()
                .get(McpProtectedResource.PATH)
                .then()
                .statusCode(200)
                .contentType("application/json")
                .header("WWW-Authenticate", is((String) null))
                .extract()
                .as(Map.class);

        assertEquals(McpProtectedResource.RESOURCE, body.get("resource"));
        assertEquals(List.of(McpProtectedResource.AUTHORIZATION_SERVER), body.get("authorization_servers"));
        assertEquals(List.of("header"), body.get("bearer_methods_supported"));
        assertEquals(
                Set.of("resource", "authorization_servers", "bearer_methods_supported"),
                body.keySet());
        String raw = given().get(McpProtectedResource.PATH).then().statusCode(200).extract().asString();
        assertFalse(raw.contains("pc-meta"));
        assertFalse(raw.contains("meta-hostname"));
        assertFalse(raw.contains("prompt"));
        assertFalse(raw.contains("client_secret"));
        assertFalse(raw.contains("client_id"));
        assertFalse(raw.contains("api_key"));
        assertFalse(raw.contains("api-key"));
        assertFalse(raw.contains("device"));
        assertEquals(before, jobs.jobCount());
    }

    @Test
    void other_methods_and_paths_stay_authenticated() {
        given().post(McpProtectedResource.PATH).then().statusCode(401);
        given().put(McpProtectedResource.PATH).then().statusCode(401);
        given().delete(McpProtectedResource.PATH).then().statusCode(401);
        given().head(McpProtectedResource.PATH).then().statusCode(401);
        given().get(McpProtectedResource.PATH + "/").then().statusCode(401);
        given().get("/api/dispatcher/jobs").then().statusCode(401).header("WWW-Authenticate", is((String) null));
        given().get("/api/dispatcher/devices").then().statusCode(401);
        given().post("/api/device/register").then().statusCode(401);
        given().get("/mcp").then().statusCode(401).header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE));
        given().get("/dispatch/mcp").then().statusCode(401).header("WWW-Authenticate", is(McpProtectedResource.CHALLENGE));

        String posted = given()
                .contentType("application/json")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_devices","arguments":{}}}
                        """)
                .post(McpProtectedResource.PATH)
                .then()
                .statusCode(401)
                .extract()
                .asString();
        assertFalse(posted.contains("authorization_servers"));
        assertFalse(posted.contains("list_devices"));
    }

    @Test
    @TestSecurity(user = "grok-meta", roles = "dispatcher")
    void a_dispatcher_token_still_reads_only_the_document() {
        Map<?, ?> body = given()
                .get(McpProtectedResource.PATH)
                .then()
                .statusCode(200)
                .extract()
                .as(Map.class);
        assertEquals(List.of("header"), body.get("bearer_methods_supported"));
        given().post(McpProtectedResource.PATH).then().statusCode(403);
    }
}
