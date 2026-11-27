package io.freedriver.architecture.fixtures.apiquality;

import java.util.ArrayList;
import java.util.List;

/** A static constant whose compact constructor keeps a mutable list. */
public record Held(List<String> items) {
    public static final Held ONE = new Held(List.of("a"));

    public Held {
        items = new ArrayList<>(items);
    }
}
