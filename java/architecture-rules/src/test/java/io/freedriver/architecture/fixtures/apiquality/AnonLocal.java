package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** The anonymous class sits in a local class, so the line names that local class. */
public class AnonLocal {
    void run() {
        class Holder {
            void go() {
                new Runnable() {
                    JsonNode node;

                    public void run() {
                    }
                }.run();
            }
        }
        new Holder().go();
    }
}
