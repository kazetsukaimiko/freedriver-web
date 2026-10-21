package io.freedriver.app.invites;

import java.util.List;

/** Open invites plus the CSRF token minted for create and cancel. */
public record InviteList(List<InviteView> invites, String csrfToken) {
}
