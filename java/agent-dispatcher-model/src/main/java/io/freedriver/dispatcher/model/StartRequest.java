package io.freedriver.dispatcher.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Where the host runs a job: {@code deliveryRef}, 1 to 128 printable ASCII characters, no space. */
public record StartRequest(
        @NotNull @Size(min = 1, max = 128) @Pattern(regexp = StartRequest.DELIVERY_REF) String deliveryRef) {

    /** Printable ASCII, no space. */
    public static final String DELIVERY_REF = "[\\x21-\\x7E]+";
}
