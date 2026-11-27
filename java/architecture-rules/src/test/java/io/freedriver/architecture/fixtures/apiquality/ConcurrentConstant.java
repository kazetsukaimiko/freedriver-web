package io.freedriver.architecture.fixtures.apiquality;

import java.util.concurrent.ConcurrentHashMap;

/** A static final concurrent map. */
public record ConcurrentConstant(String name) {
    public static final ConcurrentHashMap<String, String> MAP = new ConcurrentHashMap<>();
}
