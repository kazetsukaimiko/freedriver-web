package io.freedriver.architecture.fixtures.hexagonbroken;

import org.jmolecules.architecture.hexagonal.Application;

/** An application class that reaches into a primary adapter. */
@Application
public class Core {

    public String run() {
        return new Edge().answer();
    }
}
