package io.freedriver.architecture.fixtures.apiquality;

import java.util.List;

/** A static constant whose compact constructor keeps an immutable copy. */
public record Copied(List<String> items) {
    public static final Copied ONE = new Copied(List.of("a"));

    public Copied {
        items = List.copyOf(items);
    }
}
