package io.freedriver.rolefixture.api;

/** A named class. An anonymous subclass inherits {@link Port} from it. */
public class HeldBase implements Port {
    @Override
    public String name() {
        return "base";
    }
}
