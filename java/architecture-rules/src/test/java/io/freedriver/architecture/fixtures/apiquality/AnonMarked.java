package io.freedriver.architecture.fixtures.apiquality;

/** An annotation on an anonymous class, and none of that annotation's member types. */
public class AnonMarked {
    void run() {
        new Runnable() {
            @Marked
            public void run() {
            }
        }.run();
    }
}
