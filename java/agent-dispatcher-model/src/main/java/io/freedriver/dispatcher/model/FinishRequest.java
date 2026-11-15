package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.Size;

/** The result of one job: whether it succeeded, and an optional note of at most 2,000 characters. */
public record FinishRequest(
        boolean succeeded,
        @Size(max = 2000) String note) {
}
