package io.freedriver.architecture.fixtures.unqualified;

import org.jmolecules.architecture.hexagonal.Port;

/** An interface with the unqualified port that is no configuration mapping. */
@Port
public interface PortInterface {
    void call();
}
