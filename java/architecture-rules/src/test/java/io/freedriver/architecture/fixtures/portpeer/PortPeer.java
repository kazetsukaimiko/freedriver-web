package io.freedriver.architecture.fixtures.portpeer;

import org.jmolecules.architecture.hexagonal.SecondaryAdapter;

/** The secondary adapter {@code PortCaller} depends on, in another package. */
@SecondaryAdapter
public class PortPeer {

    public void touch() {
    }
}
