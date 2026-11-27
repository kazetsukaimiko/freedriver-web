package io.freedriver.architecture.fixtures.apiquality;

import java.util.List;

/** A record method whose lambda only maps and filters values. */
public record ValueLambda(List<String> items) {
    public List<String> upper() {
        return items.stream().map(String::toUpperCase).filter(item -> !item.isEmpty()).toList();
    }
}
