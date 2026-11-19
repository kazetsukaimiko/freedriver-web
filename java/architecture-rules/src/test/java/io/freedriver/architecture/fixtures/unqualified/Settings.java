package io.freedriver.architecture.fixtures.unqualified;

import io.smallrye.config.ConfigMapping;
import org.jmolecules.architecture.hexagonal.Port;

/** A configuration interface both sides read: the one place the unqualified port passes. */
@Port
@ConfigMapping(prefix = "fixture")
public interface Settings {
    String value();
}
