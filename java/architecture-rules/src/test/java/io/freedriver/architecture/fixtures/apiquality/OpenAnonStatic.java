package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A static initializer whose anonymous class opens a file. */
public record OpenAnonStatic(String path) {
    static {
        new Runnable() {
            public void run() {
                try {
                    Files.readString(Path.of("x"));
                } catch (IOException e) {
                    // The call is the violation.
                }
            }
        }.run();
    }
}
