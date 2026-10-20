package io.freedriver.app.security;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.http.Cookie;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A signed-in browser session hits {@code /logout}. Quarkus clears {@code q_session}
 * and redirects to the provider end-session endpoint with {@code id_token_hint}.
 */
@QuarkusTest
@TestProfile(PortalLogoutTest.OidcOn.class)
@QuarkusTestResource(value = PortalLogoutOidc.class, restrictToAnnotatedClass = true)
class PortalLogoutTest {

    /**
     * {@code %test.quarkus.oidc.enabled=false} is fixed when the test application is built.
     * A profile turns the web-app tenant on for this class. The wiremock resource supplies the issuer.
     */
    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.enabled", "true",
                    "quarkus.oidc.application-type", "web-app",
                    "quarkus.oidc.client-id", "freedriver-api",
                    "quarkus.oidc.credentials.secret", "secret",
                    "quarkus.oidc.authentication.cookie-force-secure", "false");
        }
    }

    @TestHTTPResource
    URL base;

    @Test
    void anonymous_home_goes_to_login() {
        given().redirects().follow(false)
                .when().get("/")
                .then()
                .statusCode(302)
                .header("Location", "/login");
    }

    @Test
    void anonymous_logout_is_not_the_spa_404() {
        Response response = given()
                .redirects().follow(false)
                .when().get("/logout")
                .then()
                .statusCode(302)
                .extract().response();
        String location = response.header("Location");
        assertTrue(location.contains("/auth/realms/quarkus"), location);
        assertFalse(response.body().asString().contains("That page drifted."), response.body().asString());
    }

    @Test
    void signed_in_logout_clears_the_session_and_sends_id_token_hint() throws Exception {
        Map<String, String> session = signIn();
        assertFalse(session.isEmpty(), "q_session");

        Response logout = given()
                .cookies(session)
                .redirects().follow(false)
                .when().get("/logout")
                .then()
                .statusCode(302)
                .extract().response();

        String location = logout.header("Location");
        assertTrue(location.contains("/protocol/openid-connect/end-session"), location);
        Map<String, String> query = query(location);
        assertTrue(query.containsKey("id_token_hint") && !query.get("id_token_hint").isBlank(), location);
        String postLogout = query.get("post_logout_redirect_uri");
        assertEquals(rootWithSlash(base), postLogout, location);
        assertFalse(postLogout.contains("*"), postLogout);
        String state = query.get("state");
        assertTrue(state != null && !state.isBlank(), location);

        assertTrue(clearsSession(logout), logout.headers().toString());
        String postLogoutCookie = setCookieValue(logout, PostLogoutReturn.COOKIE);
        assertEquals(state, postLogoutCookie, logout.headers().toString());

        given()
                .cookie(PostLogoutReturn.COOKIE, postLogoutCookie)
                .redirects().follow(false)
                .when().get("/?state=" + state)
                .then()
                .statusCode(302)
                .header("Location", "/login");
    }

    private Map<String, String> signIn() throws Exception {
        try (WebClient webClient = new WebClient()) {
            webClient.getOptions().setCssEnabled(false);
            webClient.getOptions().setJavaScriptEnabled(false);
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
            webClient.getOptions().setRedirectEnabled(true);
            HtmlPage page = webClient.getPage(base + "login");
            HtmlForm form = page.getForms().get(0);
            form.getInputByName("username").type("alice");
            form.getInputByName("password").type("alice");
            form.getInputByValue("login").click();

            Map<String, String> session = new LinkedHashMap<>();
            for (Cookie cookie : webClient.getCookieManager().getCookies()) {
                if (cookie.getName().startsWith("q_session")) {
                    session.put(cookie.getName(), cookie.getValue());
                }
            }
            return session;
        }
    }

    /** Quarkus appends post-logout-path {@code /}, so the redirect always ends with a slash. */
    private static String rootWithSlash(URL base) {
        URI uri = URI.create(base.toString());
        return uri.getScheme() + "://" + uri.getAuthority() + "/";
    }

    private static Map<String, String> query(String location) {
        Map<String, String> query = new LinkedHashMap<>();
        int mark = location.indexOf('?');
        if (mark < 0) {
            return query;
        }
        for (String pair : location.substring(mark + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            query.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    private static boolean clearsSession(Response response) {
        return response.headers().getValues("Set-Cookie").stream().anyMatch(value -> {
            String lower = value.toLowerCase();
            return lower.startsWith("q_session") && lower.contains("max-age=0");
        });
    }

    private static String setCookieValue(Response response, String name) {
        String prefix = name + "=";
        for (String header : response.headers().getValues("Set-Cookie")) {
            if (!header.startsWith(prefix)) {
                continue;
            }
            String rest = header.substring(prefix.length());
            int end = rest.indexOf(';');
            return end < 0 ? rest : rest.substring(0, end);
        }
        return "";
    }
}
