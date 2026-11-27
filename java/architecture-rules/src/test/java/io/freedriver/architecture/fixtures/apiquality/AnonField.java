package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** A refused type inside an anonymous class written as a field initializer. */
public class AnonField {
    final Runnable task = new Runnable() {
        JsonNode node;

        public void run() {
        }
    };
}
