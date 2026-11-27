package jakarta.ws.rs.client;

/** A stand-in for the Jakarta client entry point. */
public final class ClientBuilder {
    public static ClientBuilder newClient() {
        return new ClientBuilder();
    }

    public ClientBuilder target(String uri) {
        return this;
    }

    public ClientBuilder request() {
        return this;
    }

    public String get() {
        return "";
    }
}
