package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A record method that opens a file. */
public record ReadFile(String path) {
    public String text() throws IOException {
        return Files.readString(Path.of(path));
    }
}
