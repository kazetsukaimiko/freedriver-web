package io.freedriver.app.invites;

import java.time.Instant;
import java.util.UUID;

/** An open invite. The code is not here; it is shown once at creation. */
public record InviteView(UUID id, String name, Instant createdAt, Instant expiresAt) {
}
