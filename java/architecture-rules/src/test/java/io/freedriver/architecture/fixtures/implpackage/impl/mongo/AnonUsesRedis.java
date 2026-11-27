package io.freedriver.architecture.fixtures.implpackage.impl.mongo;

import io.freedriver.architecture.fixtures.implpackage.impl.redis.RedisPort;

/** An implementation that names another implementation only inside an anonymous class. */
public class AnonUsesRedis {
    void run() {
        new Runnable() {
            RedisPort port;

            public void run() {
            }
        }.run();
    }
}
