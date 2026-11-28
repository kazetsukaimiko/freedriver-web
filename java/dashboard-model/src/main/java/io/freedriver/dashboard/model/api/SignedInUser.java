package io.freedriver.dashboard.model.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

/**
 * The signed-in caller. {@code subject} is the verified token's OIDC {@code sub}.
 * It is always present and non-blank on a 200.
 * {@code name} is that token's OIDC {@code name} claim, used as-is.
 * A blank claim is absent. When {@code name} is absent, the key is omitted
 * and is never sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignedInUser(@NotBlank String subject, String name) {

    /**
     * A blank {@code name} is the same as an absent claim, so the JSON key stays off.
     */
    public SignedInUser {
        if (name != null && name.isBlank()) {
            name = null;
        }
    }
}
