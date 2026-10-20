package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostLogoutReturnTest {

    @Test
    void home_without_the_cookie_continues() {
        assertEquals(PostLogoutReturn.Action.CONTINUE, PostLogoutReturn.decide("/", List.of("abc"), null));
        assertEquals(PostLogoutReturn.Action.CONTINUE, PostLogoutReturn.decide("/", List.of(), ""));
    }

    @Test
    void other_paths_ignore_the_cookie() {
        assertEquals(
                PostLogoutReturn.Action.CONTINUE,
                PostLogoutReturn.decide("/changelog", List.of("nope"), "abc"));
    }

    @Test
    void stale_cookie_without_state_is_cleared() {
        assertEquals(PostLogoutReturn.Action.CLEAR, PostLogoutReturn.decide("/", List.of(), "abc"));
        assertEquals(PostLogoutReturn.Action.CLEAR, PostLogoutReturn.decide("/", null, "abc"));
    }

    @Test
    void matching_state_is_cleared() {
        assertEquals(PostLogoutReturn.Action.CLEAR, PostLogoutReturn.decide("/", List.of("abc"), "abc"));
    }

    @Test
    void mismatched_or_repeated_state_is_rejected() {
        assertEquals(PostLogoutReturn.Action.REJECT, PostLogoutReturn.decide("/", List.of("nope"), "abc"));
        assertEquals(PostLogoutReturn.Action.REJECT, PostLogoutReturn.decide("/", List.of("abc", "abc"), "abc"));
        assertEquals(PostLogoutReturn.Action.REJECT, PostLogoutReturn.decide("/", List.of(""), "abc"));
    }
}
