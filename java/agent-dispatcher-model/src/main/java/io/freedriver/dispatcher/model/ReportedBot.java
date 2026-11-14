package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One bot a host publishes. State is idle or busy. Activity is a short note.
 */
public record ReportedBot(
        @NotBlank @Size(max = 64) String name,
        @NotNull @Pattern(regexp = "(?i)idle|busy") String state,
        @Size(max = 500) String activity) {
}
