package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A nested anonymous class that opens a file, reported under the method that holds it. */
public record OpenAnonNested(String path) {
    public String text() {
        return new Runnable() {
            public void run() {
                new Runnable() {
                    public void run() {
                        try {
                            Files.readString(Path.of(path));
                        } catch (IOException e) {
                            // The call is the violation.
                        }
                    }
                }.run();
            }
        }.toString();
    }
}
