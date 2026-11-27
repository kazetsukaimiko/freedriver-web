package io.freedriver.architecture.fixtures.apiquality;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A local HashSet in a method the static initializer never calls. */
public record LocalSet(String name) {
    public Set<String> copy(List<String> items) {
        HashSet<String> set = new HashSet<>(items);
        return Set.copyOf(set);
    }
}
