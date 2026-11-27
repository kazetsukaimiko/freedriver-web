package io.freedriver.architecture.fixtures.apiquality;

/** A record method that builds a MicroProfile REST client. */
public record RestFetch(String name) {
    public Object build() {
        return org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder().build(Object.class);
    }
}
