package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A lambda body that opens a file, reported under the method that holds it. */
public record OpenLambda(String path) {
    public String text() {
        return List.of(path).stream().map(item -> {
            try {
                return Files.readString(Path.of(item));
            } catch (IOException e) {
                return "";
            }
        }).findFirst().orElse("");
    }
}
