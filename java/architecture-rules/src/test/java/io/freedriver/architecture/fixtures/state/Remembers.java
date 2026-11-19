package io.freedriver.architecture.fixtures.state;

import java.util.concurrent.ConcurrentHashMap;

/** Keeps open commands in this process's memory. */
public class Remembers {

    private final ConcurrentHashMap<String, String> waiters = new ConcurrentHashMap<>();

    public String waiter(String id) {
        return waiters.get(id);
    }
}
