package io.freedriver.architecture.fixtures.beanstate;

import java.util.List;
import org.jmolecules.architecture.hexagonal.SecondaryPort;

/** A stored row, like a Panache entity: public mutable fields, and no bean. */
@SecondaryPort
public class Record {

    public String id;
    public List<String> tags;
}
