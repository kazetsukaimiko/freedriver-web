package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** The same refused type, once on the class and once inside an anonymous class. */
public class AnonTwice {
    JsonNode field;

    void run() {
        new Runnable() {
            JsonNode node;

            public void run() {
            }
        }.run();
    }
}
