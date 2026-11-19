package io.freedriver.architecture.fixtures.hexagon;

import org.jmolecules.architecture.hexagonal.SecondaryAdapter;

/** The secondary adapter behind the secondary port. */
@SecondaryAdapter
public class StoreAdapter implements Store {

    @Override
    public String load() {
        return "stored";
    }
}
