package io.freedriver.architecture.fixtures.apiquality;

/** A record method that calls the Jakarta client API. */
public record Fetch(String name) {
    public String load() {
        return jakarta.ws.rs.client.ClientBuilder.newClient().target("http://example").request().get();
    }
}
