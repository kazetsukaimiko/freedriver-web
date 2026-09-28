package io.freedriver.sms.support;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Asserts two HTTP answers are the same: status, body bytes, and every header except Date. */
public final class SameAnswer {

    private SameAnswer() {
    }

    public static void assertSameAnswer(ExtractableResponse<Response> expected, ExtractableResponse<Response> actual) {
        assertEquals(expected.statusCode(), actual.statusCode(), "status");
        assertArrayEquals(expected.asByteArray(), actual.asByteArray(),
                () -> "body: " + expected.asString() + " vs " + actual.asString());
        assertEquals(headers(expected), headers(actual), "headers");
    }

    private static Map<String, String> headers(ExtractableResponse<Response> response) {
        Map<String, String> out = new TreeMap<>();
        response.headers().asList().stream()
                .filter(h -> !h.getName().equalsIgnoreCase("date"))
                .forEach(h -> out.merge(h.getName().toLowerCase(), h.getValue(), (a, b) -> a + "," + b));
        return out;
    }
}
