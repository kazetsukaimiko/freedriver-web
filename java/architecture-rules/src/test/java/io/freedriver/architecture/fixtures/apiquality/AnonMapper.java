package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** An API record whose only refused dependency sits inside an anonymous class. */
public record AnonMapper(String name) {
    public void read() {
        new Runnable() {
            JsonNode node;

            public void run() {
            }
        }.run();
    }
}
