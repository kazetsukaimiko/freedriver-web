package io.freedriver.dispatcher.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The host's roster: its hostname and 1 to 32 bots, each idle or busy. */
public record HostReport(
        @NotBlank @Size(max = 253) String hostname,
        @NotNull @Size(min = 1, max = 32) List<@Valid @NotNull ReportedBot> bots) {
}
