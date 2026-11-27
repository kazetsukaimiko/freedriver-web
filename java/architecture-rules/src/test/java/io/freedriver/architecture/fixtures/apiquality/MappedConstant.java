package io.freedriver.architecture.fixtures.apiquality;

import java.util.Map;

/** A static Map.of field and a static constant of the record's own type. */
public record MappedConstant(String name) {
    public static final Map<String, String> INDEX = Map.of("a", "b");
    public static final MappedConstant SELF = new MappedConstant("a");
}
