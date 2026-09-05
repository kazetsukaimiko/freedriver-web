package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymousDocumentRedirectTest {

    @Test
    void oidc_off_never_redirects() {
        assertFalse(AnonymousDocumentRedirect.shouldRedirect(false, "GET", "/", null, true));
        assertFalse(AnonymousDocumentRedirect.shouldRedirect(false, "GET", "/dashboard", null, true));
    }

    @Test
    void signed_in_document_stays() {
        assertFalse(AnonymousDocumentRedirect.shouldRedirect(true, "GET", "/", null, false));
    }

    @Test
    void xhr_never_redirects() {
        assertFalse(AnonymousDocumentRedirect.shouldRedirect(
                true, "GET", "/", "XMLHttpRequest", true));
        assertFalse(AnonymousDocumentRedirect.shouldRedirect(
                true, "GET", "/api/appliances", "XMLHttpRequest", true));
    }

    @Test
    void anonymous_document_goes_to_login_when_oidc_on() {
        assertTrue(AnonymousDocumentRedirect.shouldRedirect(true, "GET", "/", null, true));
        assertTrue(AnonymousDocumentRedirect.shouldRedirect(true, "GET", "/dashboard", null, true));
        assertTrue(AnonymousDocumentRedirect.shouldRedirect(true, "HEAD", "/", null, true));
    }

    @Test
    void api_assets_login_and_static_are_not_documents() {
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/api/appliances"));
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/api/hello"));
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/q/health"));
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/assets/freedriver/pages/freedriver-denied.png"));
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/login"));
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/favicon.png"));
        assertFalse(AnonymousDocumentRedirect.isSpaDocument("/index.js"));
        assertTrue(AnonymousDocumentRedirect.isSpaDocument("/"));
        assertTrue(AnonymousDocumentRedirect.isSpaDocument("/dashboard"));
        assertTrue(AnonymousDocumentRedirect.isSpaDocument("/no-such-page"));
    }
}
