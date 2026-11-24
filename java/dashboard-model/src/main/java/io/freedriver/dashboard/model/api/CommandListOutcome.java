package io.freedriver.dashboard.model.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Per-entry result of a command list. The JSON value is the lowercase name. */
public enum CommandListOutcome {
    @JsonProperty("confirmed") CONFIRMED,
    @JsonProperty("timeout") TIMEOUT,
    @JsonProperty("scheduled") SCHEDULED,
    @JsonProperty("sent") SENT
}
