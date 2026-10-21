package io.freedriver.sms.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sms-model, sms-api, and sms-client are Java 21 class files and do not bundle Keycloak's APIs. */
class JavaReleaseTest {

    private static final int JAVA_21 = 65;

    @Test
    void three_modules_are_java_21_and_do_not_bundle_jackson_or_jakarta() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        for (String module : new String[] {"sms-model", "sms-api", "sms-client"}) {
            Path classes = here.resolve("..").resolve(module).resolve("target/classes").normalize();
            assertTrue(Files.isDirectory(classes), classes.toString());
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    String relative = classes.relativize(file).toString().replace('\\', '/');
                    assertForbidden(module, relative);
                    assertEquals(JAVA_21, major(file), relative);
                    if ("sms-client".equals(module)) {
                        byte[] bytes = Files.readAllBytes(file);
                        String utf8 = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                        assertTrue(!utf8.contains("jakarta/ws/rs/client"), relative);
                        assertTrue(!utf8.contains("io/quarkus"), relative);
                    }
                }
            }
        }
    }

    private static void assertForbidden(String module, String relative) {
        assertTrue(!relative.startsWith("com/fasterxml/"), module + " bundled " + relative);
        assertTrue(!relative.startsWith("jakarta/"), module + " bundled " + relative);
        assertTrue(!relative.startsWith("io/quarkus/"), module + " bundled " + relative);
        assertTrue(!relative.startsWith("org/jboss/resteasy/"), module + " bundled " + relative);
    }

    private static int major(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(8);
            assertEquals(8, header.length);
            return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        }
    }
}
