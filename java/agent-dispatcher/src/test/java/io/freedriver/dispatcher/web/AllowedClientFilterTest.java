package io.freedriver.dispatcher.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AllowedClientFilter} pairs each OIDC client id with its role. */
class AllowedClientFilterTest {

    @Test
    void client_filter_pairs_each_client_with_its_role() {
        assertTrue(AllowedClientFilter.allowed(false, false, "other", false, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, true, null, false, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, false, "freedriver-dispatcher", true, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, false, "freedriver-openbot", false, true, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-openbot", true, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-dispatcher", false, true, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "shizuku", false, true, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertTrue(AllowedClientFilter.allowed(true, false, "freedriver-portal-jobs", false, false, true, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-portal-jobs", true, false, false, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
        assertFalse(AllowedClientFilter.allowed(true, false, "freedriver-dispatcher", false, false, true, "freedriver-dispatcher", "freedriver-openbot", "freedriver-portal-jobs"));
    }
}
