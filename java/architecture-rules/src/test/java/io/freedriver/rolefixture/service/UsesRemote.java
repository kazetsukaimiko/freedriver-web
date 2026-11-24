package io.freedriver.rolefixture.service;

import io.freedriver.otherfixture.service.Remote;

/** Service code that reaches into another module's service package. */
public class UsesRemote {
    public String call(Remote remote) {
        return remote.answer();
    }
}
