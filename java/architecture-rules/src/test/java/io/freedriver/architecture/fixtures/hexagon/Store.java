package io.freedriver.architecture.fixtures.hexagon;

import org.jmolecules.architecture.hexagonal.SecondaryPort;

/** The secondary port the core calls. */
@SecondaryPort
public interface Store {

    String load();
}
