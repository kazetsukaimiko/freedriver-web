package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** A refused type used only inside an anonymous class. */
public class AnonRefused {
    void run() {
        new Runnable() {
            JsonNode node;

            public void run() {
            }
        }.run();
    }
}
