package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;

/** The anonymous class sits in a member class, so the line names that member class. */
public class AnonMember {
    static class Inner {
        void run() {
            new Runnable() {
                JsonNode node;

                public void run() {
                }
            }.run();
        }
    }
}
