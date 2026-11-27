package io.freedriver.architecture.fixtures.implpackage.impl.mongo;

/** An implementation whose anonymous class names a class of the same implementation. */
public class AnonUsesOwn {
    void run() {
        new Runnable() {
            MongoHelper helper;

            public void run() {
            }
        }.run();
    }
}
