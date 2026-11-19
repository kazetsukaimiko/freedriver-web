package io.freedriver.architecture.fixtures.hexagon;

/** The edge: a primary adapter by its package, calling the primary port. */
public class Resource {

    private final Service service;

    public Resource(Service service) {
        this.service = service;
    }

    public int kinds() {
        return service.kind() == null ? 0 : Service.Kind.values().length;
    }
}
