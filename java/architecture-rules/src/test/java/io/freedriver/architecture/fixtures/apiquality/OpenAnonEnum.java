package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A field initializer whose anonymous class opens a file. The line names the constructor. */
public enum OpenAnonEnum {
    ONE;

    final Runnable task = new Runnable() {
        public void run() {
            try {
                Files.readString(Path.of("x"));
            } catch (IOException e) {
                // The call is the violation.
            }
        }
    };
}
