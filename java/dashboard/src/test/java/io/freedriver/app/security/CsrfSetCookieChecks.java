package io.freedriver.app.security;

import io.restassured.response.Response;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CsrfSetCookieChecks {

    private CsrfSetCookieChecks() {
    }

    static void assertMapCookie(Response response, boolean secure) {
        assertEquals(200, response.statusCode(), response.asString());
        String header = csrfHeader(response);
        String[] parts = header.split(";");
        String pair = parts[0].trim();
        assertTrue(pair.startsWith(CsrfFilter.COOKIE + "="), header);
        String value = pair.substring(CsrfFilter.COOKIE.length() + 1).trim();
        assertFalse(value.isBlank(), header);
        assertEquals(value, response.jsonPath().getString("csrfToken"), header);

        boolean sawSecure = false;
        boolean sawHttpOnly = false;
        String path = null;
        String sameSite = null;
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            if (attr.isEmpty()) {
                continue;
            }
            int eq = attr.indexOf('=');
            String name = (eq < 0 ? attr : attr.substring(0, eq)).trim();
            String attrValue = eq < 0 ? "" : stripQuotes(attr.substring(eq + 1).trim());
            if (name.equalsIgnoreCase("Secure")) {
                sawSecure = true;
            } else if (name.equalsIgnoreCase("HttpOnly")) {
                sawHttpOnly = true;
            } else if (name.equalsIgnoreCase("Path")) {
                path = attrValue;
            } else if (name.equalsIgnoreCase("SameSite")) {
                sameSite = attrValue;
            }
        }
        assertEquals(secure, sawSecure, header);
        assertTrue(sawHttpOnly, header);
        assertEquals("/", path, header);
        assertNotNull(sameSite, header);
        assertTrue(sameSite.equalsIgnoreCase("Lax"), header);
    }

    private static String csrfHeader(Response response) {
        List<String> values = response.getHeaders().getValues("Set-Cookie");
        assertNotNull(values, "Set-Cookie");
        return values.stream()
                .filter(value -> value.startsWith(CsrfFilter.COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no csrf Set-Cookie in " + values));
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
