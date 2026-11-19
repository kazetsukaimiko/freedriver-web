package io.freedriver.architecture.fixtures.hexagon;

import org.jmolecules.architecture.hexagonal.Application;

import java.util.List;

/** The application core: it implements the primary port and calls the secondary port. */
@Application
public class ServiceCore implements Service {

    private final Store store;

    public ServiceCore(Store store) {
        this.store = store;
    }

    @Override
    public Kind kind() {
        Row row = new Row(store.load(), List.of());
        return row.id() == null ? Kind.ONE : Kind.TWO;
    }

    /** A value inside the core; it takes the role of {@link ServiceCore}. */
    record Row(String id, List<String> hosts) {
    }
}
