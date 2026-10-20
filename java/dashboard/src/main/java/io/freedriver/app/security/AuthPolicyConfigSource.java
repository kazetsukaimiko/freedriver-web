package io.freedriver.app.security;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads {@code auth-policy.properties} from the classpath. SmallRye does not accept a
 * {@code classpath:} location, and this file is the one code owners review.
 */
public final class AuthPolicyConfigSource implements ConfigSource {

    static final String RESOURCE = "auth-policy.properties";

    /**
     * Above {@code application.properties} (250). {@link AuthPolicyLockInterceptor} is what
     * keeps a higher-ordinal source from replacing these keys.
     */
    static final int ORDINAL = 270;

    private final Map<String, String> properties;

    public AuthPolicyConfigSource() {
        this.properties = load(Thread.currentThread().getContextClassLoader());
    }

    static Map<String, String> load(ClassLoader loader) {
        ClassLoader source = loader == null ? AuthPolicyConfigSource.class.getClassLoader() : loader;
        try (InputStream in = source.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            Properties raw = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                raw.load(reader);
            }
            return Map.copyOf(raw.entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> String.valueOf(entry.getValue()))));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return RESOURCE;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }
}
