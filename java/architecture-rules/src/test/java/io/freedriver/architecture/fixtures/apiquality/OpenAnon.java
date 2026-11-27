package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** An anonymous class that opens a file, reported under the method that holds it. */
public record OpenAnon(String path) {
    public String text() {
        return new Supplier<String>() {
            public String get() {
                try {
                    return Files.readString(Path.of(path));
                } catch (IOException e) {
                    return "";
                }
            }
        }.get();
    }
}
