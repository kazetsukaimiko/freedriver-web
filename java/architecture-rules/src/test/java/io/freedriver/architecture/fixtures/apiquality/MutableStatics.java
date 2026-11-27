package io.freedriver.architecture.fixtures.apiquality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Static fields filled with mutable collections. */
public record MutableStatics(String name) {
    public static final Map<String, String> MAP = new HashMap<>();
    public static final List<String> LIST = new ArrayList<>();
}
