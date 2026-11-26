package org.eclipse.microprofile.rest.client;

/** A stand-in for the MicroProfile REST client builder. */
public final class RestClientBuilder {
    public static RestClientBuilder newBuilder() {
        return new RestClientBuilder();
    }

    public <T> T build(Class<T> api) {
        return null;
    }
}
