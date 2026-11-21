package io.freedriver.architecture.fixtures.state;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Static constants, and final fields of immutable types: no state. */
public class Constants {

    static final Set<String> NAMES = Set.of("report_host", "finish_job");
    static final List<String> ORDER = List.of("first", "second");
    private static final Duration WAIT = Duration.ofSeconds(1);

    private final String name;

    public Constants(String name) {
        this.name = name;
    }

    /** A value the class hands out; its fields are the caller's, not the bean's. */
    record Row(String id, List<String> hosts) {
    }

    public Row row() {
        return new Row(name, ORDER);
    }

    public Duration waitFor() {
        return NAMES.contains(name) ? WAIT : Duration.ZERO;
    }
}
