package io.freedriver.architecture.fixtures.apiquality;

import java.util.List;
import java.util.function.Supplier;

/** An API record whose anonymous class uses only allowed types. */
public record AnonValue(List<String> items) {
    public int size() {
        return new Supplier<Integer>() {
            public Integer get() {
                return items.size();
            }
        }.get();
    }
}
