package io.freedriver.app.schedule;

import java.time.Instant;
import java.util.UUID;

/** One row in {@code scheduled-switches}. */
public record ScheduledSwitch(
        String id,
        UUID instanceId,
        String applianceName,
        boolean on,
        Instant dueAt,
        String subject) {
}
