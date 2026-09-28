package io.freedriver.sms.support;

import io.freedriver.sms.security.SharedSecretFilter;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

import java.util.Map;

import static io.restassured.RestAssured.given;

/** Keycloak-style calls with the test shared secret. */
public final class SmsCalls {

    public static final String SECRET = "test-only-shared-secret";

    private SmsCalls() {
    }

    public static ValidatableResponse send(String phone) {
        return given().header(SharedSecretFilter.HEADER, SECRET).contentType(ContentType.JSON)
                .body(Map.of("phone", phone)).post("/otp/send").then();
    }

    public static ValidatableResponse verify(String phone, String code) {
        return given().header(SharedSecretFilter.HEADER, SECRET).contentType(ContentType.JSON)
                .body(Map.of("phone", phone, "code", code)).post("/otp/verify").then();
    }
}
