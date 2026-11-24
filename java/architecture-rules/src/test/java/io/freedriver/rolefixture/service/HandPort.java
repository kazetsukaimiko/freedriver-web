package io.freedriver.rolefixture.service;

import io.freedriver.rolefixture.api.Port;

/** Implements an API interface from a service package. */
public class HandPort implements Port {
    @Override
    public String name() {
        return "hand";
    }
}
