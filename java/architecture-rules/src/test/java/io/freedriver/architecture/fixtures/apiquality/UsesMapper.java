package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.ObjectMapper;

/** An API type that depends on Jackson's ObjectMapper. */
public interface UsesMapper {
    ObjectMapper mapper();
}
