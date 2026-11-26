package io.freedriver.architecture.fixtures.apiquality;

import java.net.URI;

/** A compact constructor may read a URI. */
public record SchemeValue(URI base) {
    public SchemeValue {
        base.getScheme();
    }
}
