package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Serves src/test/resources/changelog/CHANGELOG.md, the test copy of the file the release job packages. */
@QuarkusTest
class ChangelogResourceTest {

    static final String FIXTURE = """
            # Changelog

            ## 2026-09 (test fixture)

            ### Features
            - Sample entry served by ChangelogResourceTest.
            """;

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void dashboard_role_reads_the_changelog() {
        given().header("X-Requested-With", "JavaScript")
                .when().get("/api/changelog")
                .then()
                .statusCode(200)
                .contentType(startsWith("text/plain"))
                .body(is(FIXTURE));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"portal-admin"})
    void portal_admin_role_reads_the_changelog() {
        given().when().get("/api/changelog")
                .then()
                .statusCode(200)
                .body(is(FIXTURE));
    }

    @Test
    @TestSecurity(user = "reader", roles = {"changelog"})
    void changelog_role_reads_the_changelog() {
        given().when().get("/api/changelog")
                .then()
                .statusCode(200)
                .body(is(FIXTURE));
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void signed_in_without_an_allowed_role_is_403() {
        given().header("X-Requested-With", "JavaScript")
                .when().get("/api/changelog")
                .then()
                .statusCode(403)
                .body(is("{}"));
    }

    @Test
    void anonymous_is_401() {
        given().header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when().get("/api/changelog")
                .then()
                .statusCode(401)
                .body(not(containsString("Changelog")));
    }
}
