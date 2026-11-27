package io.freedriver.architecture.fixtures.apiquality;

/** An abstract method may return a Jakarta response type. */
public interface ResponseApi {
    jakarta.ws.rs.core.Response read();
}
