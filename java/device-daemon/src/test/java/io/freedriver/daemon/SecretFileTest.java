package io.freedriver.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretFileTest {

    @TempDir
    Path dir;

    @Test
    void mode_0600_is_required_and_the_secret_stays_out_of_the_error() throws Exception {
        Path file = dir.resolve("device.secret");
        Files.writeString(file, "test-device-secret\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> SecretFile.read(file));
        assertFalse(rejected.getMessage().contains("test-device-secret"));

        Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        SecretFile.Material material = SecretFile.read(file);
        assertEquals("test-device-secret", material.secret());
        assertEquals("test-device-secret\n", material.raw());
    }

    @Test
    void symlink_is_refused() throws Exception {
        Path target = dir.resolve("target");
        Files.writeString(target, "test-device-secret\n");
        Files.setPosixFilePermissions(target, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Path link = dir.resolve("link");
        Files.createSymbolicLink(link, target);

        assertThrows(IllegalArgumentException.class, () -> SecretFile.read(link));
    }

    @Test
    void short_secret_is_refused_without_echoing_it() throws Exception {
        Path file = dir.resolve("short.secret");
        Files.writeString(file, "abc\n");
        Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> SecretFile.read(file));
        assertFalse(rejected.getMessage().contains("abc"));
    }
}
