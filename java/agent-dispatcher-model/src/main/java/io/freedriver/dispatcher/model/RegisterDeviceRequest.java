package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterDeviceRequest(
        @NotBlank @Size(max = 253) String hostname,
        @NotNull @Size(min = 1, max = 32) List<@NotBlank @Size(max = 64) String> agents) {
}
