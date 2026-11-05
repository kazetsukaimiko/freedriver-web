package io.freedriver.app.metrics;

import jakarta.enterprise.context.ApplicationScoped;

@BlockingIo
@ApplicationScoped
public class BlockingIoProbe {
    public void ping() {}
}
