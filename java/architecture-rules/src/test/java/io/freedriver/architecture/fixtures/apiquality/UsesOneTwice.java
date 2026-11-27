package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.ObjectMapper;

/** One refused class, used as a field, a parameter and two calls. */
public class UsesOneTwice {
    ObjectMapper field;

    void m(ObjectMapper param) {
        field.copy();
        param.getTypeFactory();
    }
}
