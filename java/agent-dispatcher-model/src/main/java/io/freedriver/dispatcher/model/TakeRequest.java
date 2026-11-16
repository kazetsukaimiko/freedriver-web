package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A take: wait up to {@code waitSeconds} (0 to 50) for a job when none is ready. {@code holding}
 * names at most 64 assignments this host still works on; their hold is renewed and they are not
 * returned. {@code holding} is optional.
 */
public record TakeRequest(
        @Min(0) @Max(50) int waitSeconds,
        @Size(max = 64) List<@NotNull UUID> holding) {
}
