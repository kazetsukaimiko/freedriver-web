package io.freedriver.app.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * YEAR-MONTH_rBUILD_NUM, same scheme as autonomy mqtt-contract (e.g. {@code 2026-08_r184}).
 * SNAPSHOT and any other string are not a published build number.
 */
final class PublishedBuild {
    static final Pattern PATTERN = Pattern.compile("^\\d{4}-\\d{2}_r\\d+$");

    private PublishedBuild() {
    }

    static String orNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return PATTERN.matcher(value).matches() ? value : null;
    }

    static String fromImplementationVersion(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg != null) {
            String published = orNull(pkg.getImplementationVersion());
            if (published != null) {
                return published;
            }
        }
        try (InputStream in = type.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                return null;
            }
            Manifest manifest = new Manifest(in);
            return orNull(manifest.getMainAttributes().getValue("Implementation-Version"));
        } catch (IOException ignored) {
            return null;
        }
    }

    static String resolve(Class<?> type, String applicationVersion) {
        String fromJar = fromImplementationVersion(type);
        if (fromJar != null) {
            return fromJar;
        }
        return orNull(applicationVersion);
    }
}
