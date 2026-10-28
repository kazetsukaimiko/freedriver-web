package io.freedriver.app.api;

import io.freedriver.app.security.CsrfFilter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.SecurityAttribute;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class McpResourceTest {

    @Test
    void anonymous_mcp_is_401() {
        given().when().get("/autonomy/mcp").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "ada", roles = {"portal-admin", "dashboard"}, attributes = {
            @SecurityAttribute(key = "sub", value = "portal-sub")
    })
    void portal_roles_without_mcp_user_are_403() {
        given().when().get("/autonomy/mcp").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "ada", roles = {"mcp-user"}, attributes = {
            @SecurityAttribute(key = "sub", value = "mcp-sub")
    })
    void mcp_user_reaches_the_path_without_set_cookie() {
        given().when().get("/autonomy/mcp")
                .then()
                .statusCode(204)
                .header("Set-Cookie", nullValue());
    }

    @Test
    @TestSecurity(user = "ada", roles = {"mcp-user"}, attributes = {
            @SecurityAttribute(key = "sub", value = "mcp-sub")
    })
    void session_cookie_on_mcp_is_401() {
        given().cookie("q_session", "portal-session")
                .when().get("/autonomy/mcp")
                .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "ada", roles = {"dashboard"}, attributes = {
            @SecurityAttribute(key = "sub", value = "dash-sub")
    })
    void cookie_and_csrf_on_appliances_stay() {
        given().when().get("/api/appliances")
                .then()
                .statusCode(200)
                .cookie(CsrfFilter.COOKIE);
    }
}
