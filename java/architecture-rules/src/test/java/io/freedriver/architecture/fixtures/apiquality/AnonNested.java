package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** A refused type inside an anonymous class that is itself inside an anonymous class. */
public class AnonNested {
    void run() {
        new Runnable() {
            public void run() {
                new Runnable() {
                    JsonNode node;

                    public void run() {
                    }
                }.run();
            }
        }.run();
    }
}
