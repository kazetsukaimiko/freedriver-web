package io.freedriver.app.instances;

/** Keycloak already has this username. House provision mints once more, then refuses. */
final class UsernameTakenException extends RuntimeException {

    UsernameTakenException() {
        super("username taken");
    }
}
