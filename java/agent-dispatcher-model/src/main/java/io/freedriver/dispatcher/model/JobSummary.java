package io.freedriver.dispatcher.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a caller's job list. The prompt stays off this row.
 */
public record JobSummary(
        UUID jobId,
        String agentName,
        String server,
        String state,
        Instant submittedAt,
        String note) {
}
