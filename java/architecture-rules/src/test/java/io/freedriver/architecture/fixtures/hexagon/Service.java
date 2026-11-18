package io.freedriver.architecture.fixtures.hexagon;

import org.jmolecules.architecture.hexagonal.PrimaryPort;

/** The primary port; its nested enum takes the port's role. */
@PrimaryPort
public interface Service {

    Kind kind();

    enum Kind {
        ONE,
        TWO
    }
}
