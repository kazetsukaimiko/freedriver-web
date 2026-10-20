package io.freedriver.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

final class SecretFile {

    record Material(String secret, String raw) {
    }

    private SecretFile() {
    }

    static Material read(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("secret file is required");
        }
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("secret file must not be a symlink");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("secret file is missing");
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!perms.equals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
                throw new IllegalArgumentException("secret file must be mode 0600");
            }
            if (Files.size(path) > 4096) {
                throw new IllegalArgumentException("secret file is too large");
            }
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String secret = raw.trim();
            if (secret.isEmpty()) {
                throw new IllegalArgumentException("secret file is empty");
            }
            if (secret.length() < 8) {
                throw new IllegalArgumentException("secret file is too short");
            }
            return new Material(secret, raw);
        } catch (UnsupportedOperationException | IOException failed) {
            throw new IllegalArgumentException("secret file must be mode 0600");
        }
    }
}
