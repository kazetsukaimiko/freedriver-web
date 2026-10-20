package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SubmitJobRequest(
        @NotNull @Size(min = 1, max = 32) List<@NotBlank @Size(max = 128) String> deviceIds,
        @NotBlank @Size(max = 64) String agentName,
        @NotBlank @Size(max = 16000) String prompt,
        @NotNull Channel channel) {
}
