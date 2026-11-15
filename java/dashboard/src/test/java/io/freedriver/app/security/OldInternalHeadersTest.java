package io.freedriver.app.security;

import io.freedriver.app.appliances.MockAutonomy;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The headers autonomy-mcp sent before #475 are not a credential: no mechanism reads them, and
 * the dashboard has no shared secret left to compare them against.
 */
@QuarkusTest
class OldInternalHeadersTest {

    @Test
    void the_old_internal_headers_are_401() {
        given().header("X-Freedriver-Internal", "test-internal-secret")
                .header("X-Freedriver-Subject", "mcp-sub")
                .header("X-Freedriver-Roles", "mcp-user,dashboard,portal-admin")
                .when()
                .get("/api/mcp/appliances")
                .then()
                .statusCode(401);
        given().header("X-Freedriver-Internal", "test-internal-secret")
                .header("X-Freedriver-Subject", "mcp-sub")
                .header("X-Freedriver-Roles", "mcp-user,dashboard,portal-admin")
                .contentType("application/json")
                .body("{\"on\":true}")
                .when()
                .post("/api/appliances/" + MockAutonomy.INSTANCE_ID + "/" + MockAutonomy.FIXTURE_NAMES.get(0))
                .then()
                .statusCode(401);
    }

    @Test
    void no_shared_secret_or_internal_header_remains_between_the_services() throws IOException {
        List<Path> roots = List.of(Path.of("src/main"), Path.of("../autonomy-mcp/src/main"),
                Path.of("../../docker-compose.yml"), Path.of("../../Caddyfile"));
        List<String> found;
        try (Stream<Path> files = roots.stream().flatMap(OldInternalHeadersTest::walk)) {
            found = files.filter(file -> mentionsTheOldSecret(read(file))).map(Path::toString).toList();
        }
        assertEquals(List.of(), found);
        assertFalse(read(Path.of("src/main/resources/application.properties")).contains("mcp.internal"));
    }

    private static boolean mentionsTheOldSecret(String text) {
        return text.contains("FREEDRIVER_MCP_INTERNAL_SECRET") || text.contains("internal-secret")
                || text.contains("X-Freedriver-Internal") || text.contains("X-Freedriver-Subject")
                || text.contains("X-Freedriver-Roles");
    }

    private static Stream<Path> walk(Path root) {
        try {
            return Files.isDirectory(root) ? Files.walk(root).filter(Files::isRegularFile).toList().stream()
                    : Stream.of(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }
}
