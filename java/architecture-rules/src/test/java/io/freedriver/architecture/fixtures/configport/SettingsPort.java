package io.freedriver.architecture.fixtures.configport;

import io.smallrye.config.ConfigMapping;
import org.jmolecules.architecture.hexagonal.Port;

/** Configuration whose {@code @Port} repeats the package's {@code @Port}. That pair is allowed. */
@Port
@ConfigMapping(prefix = "fixture.configport")
public interface SettingsPort {
    String value();
}
