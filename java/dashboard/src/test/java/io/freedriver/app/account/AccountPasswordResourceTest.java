package io.freedriver.app.account;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AccountPasswordResourceTest {

    @Test
    @TestSecurity(user = "ada", roles = {"dashboard"})
    void dashboard_role_starts_update_password_on_login() {
        var response = given().header("X-Requested-With", "JavaScript")
                .when().get("/api/account/password")
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("url", is("/login"))
                .extract()
                .response();
        String setCookie = String.join("\n", response.getHeaders().getValues("Set-Cookie"));
        assertTrue(setCookie.contains(PasswordAction.COOKIE + "=" + PasswordAction.UPDATE_PASSWORD), setCookie);
        assertTrue(setCookie.toLowerCase().contains("httponly"), setCookie);
        assertFalse(setCookie.toLowerCase().contains("password="), setCookie);
        assertFalse(response.asString().contains("twilio"), response.asString());
        assertFalse(response.asString().contains("freedriver-grok-mcp"), response.asString());
        assertFalse(response.asString().contains("grok.com"), response.asString());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"portal-admin"})
    void portal_admin_role_starts_update_password() {
        given().when().get("/api/account/password")
                .then()
                .statusCode(200)
                .body("url", is("/login"));
    }

    @Test
    @TestSecurity(user = "bob", roles = {"user"})
    void signed_in_without_an_allowed_role_is_403() {
        given().header("X-Requested-With", "JavaScript")
                .when().get("/api/account/password")
                .then()
                .statusCode(403)
                .body(is("{}"));
    }

    @Test
    void anonymous_is_401() {
        given().header("X-Requested-With", "JavaScript")
                .redirects().follow(false)
                .when().get("/api/account/password")
                .then()
                .statusCode(401)
                .body(not(containsString("login")));
    }
}
