package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.HostPaths;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/** The host API with OIDC on: anonymous is 401, the device client's token reaches it, others are 403. */
@QuarkusTest
@TestProfile(HostResourceOidcTest.OidcOn.class)
@QuarkusTestResource(value = HostOidc.class, restrictToAnnotatedClass = true)
class HostResourceOidcTest {

    public static class OidcOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.enabled", "true",
                    "quarkus.oidc.token.issuer", "https://server.example.com");
        }
    }

    @Test
    void an_anonymous_call_is_401_on_every_route() {
        String any = UUID.randomUUID().toString();
        given().contentType("application/json")
                .body("{\"hostname\":\"x.local\",\"bots\":[{\"name\":\"yuni\",\"state\":\"idle\"}]}")
                .post(HostPaths.ROOT + HostPaths.REPORT).then().statusCode(401);
        given().contentType("application/json").body("{\"waitSeconds\":0}")
                .post(HostPaths.ROOT + HostPaths.TAKE).then().statusCode(401);
        given().contentType("application/json").body("{\"deliveryRef\":\"ref\"}")
                .post(HostResourceTest.path(HostPaths.START, any)).then().statusCode(401);
        given().contentType("application/json").body("{\"succeeded\":true}")
                .post(HostResourceTest.path(HostPaths.FINISH, any)).then().statusCode(401);
    }

    @Test
    void the_device_clients_token_takes_and_the_dispatcher_clients_token_is_403() {
        given().auth().oauth2(token("freedriver-openbot", "device"))
                .contentType("application/json").body("{\"waitSeconds\":0}")
                .post(HostPaths.ROOT + HostPaths.TAKE).then().statusCode(200);
        given().auth().oauth2(token("freedriver-dispatcher", "dispatcher"))
                .contentType("application/json").body("{\"waitSeconds\":0}")
                .post(HostPaths.ROOT + HostPaths.TAKE).then().statusCode(403);
    }

    /** The device role is accepted only from the device client; that pairing guards this route behind Caddy. */
    @Test
    void a_device_role_from_another_client_is_403() {
        for (String client : new String[] {"freedriver-dispatcher", "freedriver-portal-jobs", "freedriver-grok-mcp"}) {
            given().auth().oauth2(token(client, "device"))
                    .contentType("application/json").body("{\"waitSeconds\":0}")
                    .post(HostPaths.ROOT + HostPaths.TAKE).then().statusCode(403);
        }
    }

    private static String token(String azp, String role) {
        return Jwt.preferredUserName(azp)
                .subject("service-account-" + azp)
                .groups(Set.of(role))
                .issuer("https://server.example.com")
                .claim("azp", azp)
                .claim("typ", "Bearer")
                .jws().keyId("1").sign("privateKey.jwk");
    }
}
