package io.freedriver.app.security;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PostLogoutReturnFilterTest {

    @Test
    void matching_state_clears_the_cookie_and_serves_the_document() {
        Response response = given()
                .cookie(PostLogoutReturn.COOKIE, "abc")
                .redirects().follow(false)
                .when().get("/?state=abc")
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .extract().response();
        assertTrue(clearsPostLogoutCookie(response), response.headers().toString());
        assertFalse(response.body().asString().contains("Sign-out could not be confirmed."));
    }

    @Test
    void mismatched_state_is_rejected_and_the_cookie_is_cleared() {
        Response response = given()
                .cookie(PostLogoutReturn.COOKIE, "abc")
                .redirects().follow(false)
                .when().get("/?state=nope")
                .then()
                .statusCode(400)
                .extract().response();
        assertTrue(response.body().asString().contains("Sign-out could not be confirmed."));
        assertTrue(clearsPostLogoutCookie(response), response.headers().toString());
    }

    @Test
    void state_without_the_cookie_stays_a_document() {
        given()
                .redirects().follow(false)
                .when().get("/?state=abc")
                .then()
                .statusCode(anyOf(is(200), is(404)));
    }

    @Test
    void stale_cookie_on_a_normal_home_load_is_cleared() {
        Response response = given()
                .cookie(PostLogoutReturn.COOKIE, "abc")
                .redirects().follow(false)
                .when().get("/")
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .extract().response();
        assertTrue(clearsPostLogoutCookie(response), response.headers().toString());
    }

    private static boolean clearsPostLogoutCookie(Response response) {
        return response.headers().getValues("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith(PostLogoutReturn.COOKIE + "=")
                        && value.toLowerCase().contains("max-age=0"));
    }
}
