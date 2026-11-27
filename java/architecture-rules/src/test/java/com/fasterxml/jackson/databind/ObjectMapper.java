package com.fasterxml.jackson.databind;

/** A stand-in for Jackson's ObjectMapper. An API module refuses this library type. */
public class ObjectMapper {
    public ObjectMapper copy() {
        return this;
    }

    public Object getTypeFactory() {
        return null;
    }
}
