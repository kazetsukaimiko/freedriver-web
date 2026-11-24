package io.freedriver.rolefixture.plain;

import io.freedriver.otherfixture.service.Remote;

/** A class whose package names no role: its module's artifact role decides which rule judges it. */
public class Loose {
    public String call(Remote remote) {
        return remote.answer();
    }
}
