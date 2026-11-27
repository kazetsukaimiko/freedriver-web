package io.freedriver.architecture.fixtures.apiquality;

/** A method body inside an anonymous class names an annotation's member type. */
public class AnonMode {
    void run() {
        new Runnable() {
            public void run() {
                Marked.Mode mode = Marked.Mode.ON;
                mode.name();
            }
        }.run();
    }
}
