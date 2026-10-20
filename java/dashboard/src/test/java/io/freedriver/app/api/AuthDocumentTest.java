package io.freedriver.app.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class AuthDocumentTest {

    @Test
    void xhr_appliances_unauthenticated_401_empty_no_names() {
        var response = given()
                .header("X-Requested-With", "XMLHttpRequest")
                .redirects().follow(false)
                .when().get("/api/appliances")
                .then()
                .statusCode(401)
                .extract();
        assertNotEquals("/login", response.header("Location"));
        String body = response.body().asString() == null ? "" : response.body().asString();
        assertFalse(body.toLowerCase().contains("<html"), body);
        assertFalse(body.contains("hallway"), body);
        assertFalse(body.contains("Cabin"), body);
        assertFalse(body.contains("Scott"), body);
        assertFalse(body.contains("Not Authenticated"), body);
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void xhr_appliances_wrong_role_403_empty_no_names() {
        given()
                .header("X-Requested-With", "XMLHttpRequest")
                .when().get("/api/appliances")
                .then()
                .statusCode(403)
                .body(is("{}"));
    }

    @Test
    void login_anonymous_is_401_not_denied_html() {
        var response = given()
                .redirects().follow(false)
                .when().get("/login")
                .then()
                .statusCode(401)
                .extract();
        String body = response.body().asString() == null ? "" : response.body().asString();
        assertFalse(body.toLowerCase().contains("<html"), body);
        assertFalse(body.contains("This account"), body);
        assertFalse(body.contains("hallway"), body);
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void login_authenticated_goes_home() {
        given().redirects().follow(false)
                .when().get("/login")
                .then()
                .statusCode(302)
                .header("Location", anyOf(equalTo("/"), endsWith("/")));
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void login_wrong_role_still_goes_home() {
        given().redirects().follow(false)
                .when().get("/login")
                .then()
                .statusCode(302)
                .header("Location", anyOf(equalTo("/"), endsWith("/")));
    }

    @Test
    void document_root_does_not_bounce_to_login_while_oidc_off() {
        var response = given()
                .redirects().follow(false)
                .when().get("/")
                .then()
                .extract();
        assertNotEquals(302, response.statusCode());
        assertNotEquals("/login", response.header("Location"));
    }
}
