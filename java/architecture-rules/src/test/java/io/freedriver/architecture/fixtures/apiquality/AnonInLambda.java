package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** A refused type inside an anonymous class that is written in a lambda. */
public class AnonInLambda {
    void run() {
        Runnable ignored = () -> new Runnable() {
            JsonNode node;

            public void run() {
            }
        }.run();
    }
}
