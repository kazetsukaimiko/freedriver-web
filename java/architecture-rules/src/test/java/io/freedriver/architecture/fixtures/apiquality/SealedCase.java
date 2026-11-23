package io.freedriver.architecture.fixtures.apiquality;

/** A record that implements an interface from its own API module. */
public record SealedCase(String id) implements Mark {
}
