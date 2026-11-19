package io.freedriver.architecture.fixtures.state;

import java.util.HashMap;
import java.util.Map;

/** A static map that fills up while the process runs. */
public class StaticCache {

    static final Map<String, String> CACHE = new HashMap<>();

    public static String cached(String key) {
        return CACHE.computeIfAbsent(key, k -> k);
    }
}
