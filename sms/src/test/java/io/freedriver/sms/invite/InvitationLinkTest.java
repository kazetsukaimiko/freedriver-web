package io.freedriver.sms.invite;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvitationLinkTest {

    @Test
    void the_number_is_the_fragment_on_the_public_base_url() {
        String phone = "+15555550100";
        String text = InvitationLink.text("https://app.freedriver.io", phone);
        assertEquals("Sign in to Freedriver: https://app.freedriver.io/login#" + phone, text);
        URI link = URI.create(text.substring(text.indexOf("https://")));
        assertEquals("https", link.getScheme());
        assertEquals("app.freedriver.io", link.getHost());
        assertEquals("/login", link.getPath());
        assertEquals(phone, link.getRawFragment());
        assertNull(link.getRawQuery());
        assertEquals(text, InvitationLink.text("https://app.freedriver.io/", phone));
    }

    @Test
    void a_base_url_with_a_query_or_fragment_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> InvitationLink.text("https://app.freedriver.io?x=1", "+15555550100"));
        assertThrows(IllegalArgumentException.class, () -> InvitationLink.text("http://app.freedriver.io", "+15555550100"));
        assertThrows(IllegalArgumentException.class, () -> InvitationLink.text("https://app.freedriver.io/#x", "+15555550100"));
    }
}
