package io.freedriver.architecture.fixtures.nested;

import org.jmolecules.architecture.hexagonal.Application;
import org.jmolecules.architecture.hexagonal.SecondaryPort;

/** An application class whose nested class carries a role of its own. */
@Application
public class Outer {

    /** Takes the role of {@link Outer}, so a role of its own is one too many. */
    @SecondaryPort
    public static class Inner {
    }
}
