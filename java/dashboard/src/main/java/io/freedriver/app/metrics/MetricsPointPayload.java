package io.freedriver.app.metrics;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire JSON for {@code io.freedriver.metrics.MetricsPoint} on the instance metrics topic.
 */
public record MetricsPointPayload(
        @JsonProperty(required = true) String key,
        @JsonProperty(required = true) String kind,
        Map<String, Object> tags,
        Instant timestamp) {

    public MetricsPointPayload {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind");
        }
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    Kind parsedKind() {
        return Kind.parse(kind);
    }

    public enum Kind {
        INCREMENT,
        COUNT,
        GAUGE;

        static Kind parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("kind");
            }
            return Kind.valueOf(raw.strip().toUpperCase());
        }
    }
}
