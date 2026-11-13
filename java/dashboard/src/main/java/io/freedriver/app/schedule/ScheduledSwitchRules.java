package io.freedriver.app.schedule;

import jakarta.ws.rs.BadRequestException;

import java.time.Instant;
import java.util.UUID;

/** What a stored switch is allowed to ask for. One day is the longest wait. */
public final class ScheduledSwitchRules {

    static final int MAX_DELAY_SECONDS = 86_400;
    /** A caller clock a few seconds ahead of this process still stores. */
    static final int PAST_SKEW_SECONDS = 5;

    private ScheduledSwitchRules() {
    }

    public static void check(UUID instanceId, String applianceName, Instant dueAt, String subject, Instant now) {
        if (instanceId == null
                || applianceName == null
                || applianceName.isBlank()
                || dueAt == null
                || subject == null
                || subject.isBlank()) {
            throw new BadRequestException();
        }
        if (dueAt.isAfter(now.plusSeconds(MAX_DELAY_SECONDS))
                || dueAt.isBefore(now.minusSeconds(PAST_SKEW_SECONDS))) {
            throw new BadRequestException();
        }
    }
}
