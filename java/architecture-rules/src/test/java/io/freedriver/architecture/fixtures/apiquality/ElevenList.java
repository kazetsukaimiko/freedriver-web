package io.freedriver.architecture.fixtures.apiquality;

import java.util.List;

/** List.of with eleven elements builds a varargs array in the static initializer. */
public record ElevenList(String name) {
    public static final List<String> VALUES = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k");
}
