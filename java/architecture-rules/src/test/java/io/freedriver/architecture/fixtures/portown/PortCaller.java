package io.freedriver.architecture.fixtures.portown;

import org.jmolecules.architecture.hexagonal.SecondaryAdapter;

/**
 * A secondary adapter placed in a {@code @Port} package. It calls another secondary adapter, which
 * is legal for an adapter and illegal for a port, so the hexagonal result says which role jMolecules
 * gave this class.
 */
@SecondaryAdapter
public class PortCaller {

    public void call() {
        new io.freedriver.architecture.fixtures.portpeer.PortPeer().touch();
    }
}
