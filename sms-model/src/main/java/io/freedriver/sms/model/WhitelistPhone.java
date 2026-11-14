package io.freedriver.sms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.Size;

/** A number listed before its Keycloak user exists. {@code name} may be null. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhitelistPhone(@UsPhoneNumber String phone, @Size(max = 40) String name) {
}
