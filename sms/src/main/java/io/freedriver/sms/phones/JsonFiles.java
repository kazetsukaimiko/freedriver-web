package io.freedriver.sms.phones;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Small durable file writes for the phone list and the agreement log. */
final class JsonFiles {

    private JsonFiles() {
    }

    static void replace(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(content));
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file.getFileName(), e);
        }
    }

    static void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append to " + file.getFileName(), e);
        }
    }
}
