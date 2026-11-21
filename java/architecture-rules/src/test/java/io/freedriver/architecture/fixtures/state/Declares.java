package io.freedriver.architecture.fixtures.state;

import io.freedriver.stereotypes.ProcessLocal;

import java.util.concurrent.ConcurrentHashMap;

/** Keeps open commands in this process's memory and says why one process is enough. */
public class Declares {

    @ProcessLocal("The replica that sends a command also receives its acknowledgement.")
    private final ConcurrentHashMap<String, String> waiters = new ConcurrentHashMap<>();

    public String waiter(String id) {
        return waiters.get(id);
    }
}
