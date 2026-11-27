package io.freedriver.architecture.fixtures.apiquality;

/** A sealed API interface. A record may map its items. */
public sealed interface Item permits ItemOne {
    String name();
}
