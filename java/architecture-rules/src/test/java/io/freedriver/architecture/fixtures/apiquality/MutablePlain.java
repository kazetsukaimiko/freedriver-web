package io.freedriver.architecture.fixtures.apiquality;

import java.util.HashMap;
import java.util.Map;

/** A plain class with a mutable static field. Rule 2 keeps only the plain-class line. */
public class MutablePlain {
    static final Map<String, String> CACHE = new HashMap<>();
}
