package io.freedriver.app.instances;

/** Returned when the user, both topic attributes, and {@code mqtt-instance} membership are in place. */
public record KeycloakUserResult(String username) {
}
