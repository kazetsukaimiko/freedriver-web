package io.freedriver.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Optional;

/**
 * How long {@code submit_job} stays open. Empty means return the job id and
 * leave the assignment queued. Zero is the same as empty. A positive value
 * waits, and the job expires if it is still open at the end.
 */
final class SubmitBlock {

    static final int MAX_SECONDS = 180;

    private SubmitBlock() {
    }

    static Optional<Duration> parse(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Optional.empty();
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("blockForUpTo");
        }
        long seconds = value.longValue();
        if (seconds < 0 || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("blockForUpTo");
        }
        if (seconds == 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(seconds));
    }
}
