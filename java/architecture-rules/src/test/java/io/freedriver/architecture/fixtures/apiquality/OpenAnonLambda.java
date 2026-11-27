package io.freedriver.architecture.fixtures.apiquality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** An anonymous class inside a lambda, reported under the method that holds the lambda. */
public record OpenAnonLambda(String path) {
    public String text() {
        return List.of(path).stream().map(item -> new Supplier<String>() {
            public String get() {
                try {
                    return Files.readString(Path.of(item));
                } catch (IOException e) {
                    return "";
                }
            }
        }.get()).findFirst().orElse("");
    }
}
