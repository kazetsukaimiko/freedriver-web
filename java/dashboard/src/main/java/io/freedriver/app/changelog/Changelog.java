package io.freedriver.app.changelog;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The changelog packaged in the app jar at {@value #RESOURCE}. The release job (#151) writes the
 * file to that classpath path. It sits outside {@code META-INF/resources}, so Quarkus never serves
 * it as a static file; {@code GET /api/changelog} is the one way to read it.
 */
@ApplicationScoped
public class Changelog {

    public static final String RESOURCE = "changelog/CHANGELOG.md";

    /** The changelog text, or empty while the jar has no changelog file. */
    public Optional<String> text() {
        return read(RESOURCE);
    }

    static Optional<String> read(String resource) {
        return Optional.ofNullable(Thread.currentThread().getContextClassLoader().getResource(resource))
                .map(Changelog::readString);
    }

    private static String readString(URL url) {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
