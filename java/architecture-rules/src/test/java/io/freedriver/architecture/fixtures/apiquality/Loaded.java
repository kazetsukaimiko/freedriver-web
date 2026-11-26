package io.freedriver.architecture.fixtures.apiquality;

import java.util.ServiceLoader;

/** A record method that loads a service. */
public record Loaded(String name) {
    public Object load() {
        return ServiceLoader.load(Object.class).findFirst().orElse(null);
    }
}
