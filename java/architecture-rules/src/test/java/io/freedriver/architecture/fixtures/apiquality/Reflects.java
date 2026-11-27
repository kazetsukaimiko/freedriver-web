package io.freedriver.architecture.fixtures.apiquality;

import java.lang.reflect.Method;

/** A record method that reflects through Class. */
public record Reflects(String name) {
    public Method find() throws Exception {
        return Class.forName(name).getDeclaredMethod("toString");
    }
}
