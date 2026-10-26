package io.freedriver.app.api;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInLinkTest {

    @Test
    void hostIsTheConfiguredBaseAndTheNumberIsTheFragment() {
        String url = SignInLink.url("https://app.freedriver.io/", "+15555550100");
        assertEquals("https://app.freedriver.io/sign-in#%2B15555550100", url);
        URI uri = URI.create(url);
        assertEquals("app.freedriver.io", uri.getHost());
        assertEquals("/sign-in", uri.getPath());
        assertNull(uri.getQuery());
        assertEquals("+15555550100", uri.getFragment());
    }

    @Test
    void aQueryOnTheBaseCannotCarryTheNumber() {
        String url = SignInLink.url("https://app.freedriver.io?phone=+15555550100&host=evil.example", "+15555550199");
        assertFalse(url.contains("?"));
        assertFalse(url.contains("evil.example"));
        assertFalse(url.contains("+15555550100"));
        assertTrue(url.startsWith("https://app.freedriver.io/sign-in#"));
        assertEquals("+15555550199", URI.create(url).getFragment());
    }

    @Test
    void refusesAMissingBaseOrANumberThatIsNotCanonical() {
        assertThrows(IllegalArgumentException.class, () -> SignInLink.url(null, "+15555550100"));
        assertThrows(IllegalArgumentException.class, () -> SignInLink.url("app.freedriver.io", "+15555550100"));
        IllegalArgumentException badPhone = assertThrows(IllegalArgumentException.class,
                () -> SignInLink.url("https://app.freedriver.io", "5555550100"));
        assertFalse(badPhone.getMessage().contains("555"));
    }

    @Test
    void pageKeepsTheFragmentInMemoryAndDoesNotReadTheQuery() {
        assertTrue(SignInLink.PAGE.contains("location.hash"));
        assertTrue(SignInLink.PAGE.contains("sessionStorage"));
        assertTrue(SignInLink.PAGE.contains(SignInLink.PHONE_KEY));
        assertTrue(SignInLink.PAGE.contains("location.replace(\"/login#\""));
        assertFalse(SignInLink.PAGE.contains("location.search"));
        assertFalse(SignInLink.PAGE.contains("URLSearchParams"));
    }
}
