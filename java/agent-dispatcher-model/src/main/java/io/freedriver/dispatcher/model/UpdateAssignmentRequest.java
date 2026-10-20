package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateAssignmentRequest(
        @NotNull AssignmentState state,
        UUID grokSessionId,
        @Size(max = 2000) String note) {
}
