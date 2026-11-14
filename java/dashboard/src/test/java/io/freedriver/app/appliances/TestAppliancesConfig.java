package io.freedriver.app.appliances;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds {@link AppliancesConfig} outside Quarkus, with the mapping's own defaults under the given values. */
public final class TestAppliancesConfig {

    private TestAppliancesConfig() {
    }

    /** Keys are relative to {@code freedriver.appliances}, for example {@code rate-limit.permits}. */
    public static AppliancesConfig of(Map<String, String> values) {
        Map<String, String> properties = new LinkedHashMap<>();
        values.forEach((key, value) -> properties.put("freedriver.appliances." + key, value));
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 200, new DurationConverter())
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .withMapping(AppliancesConfig.class)
                .build();
        return config.getConfigMapping(AppliancesConfig.class);
    }
}
