package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Two refused classes. */
public class UsesTwo {
    ObjectMapper mapper;
    JsonNode node;

    void m() {
        mapper.copy();
        node.isEmpty();
    }
}
