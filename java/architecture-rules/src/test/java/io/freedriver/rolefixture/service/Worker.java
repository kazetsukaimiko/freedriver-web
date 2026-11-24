package io.freedriver.rolefixture.service;

import io.freedriver.otherfixture.api.RemoteContract;
import io.freedriver.rolefixture.api.Port;

/** Service code that uses its own module's service and API code and another module's API package. */
public class Worker {
    public String work(Port port, Helper helper, RemoteContract remote) {
        return port.name() + helper.help() + remote.id();
    }
}
