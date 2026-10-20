package io.freedriver.app.invites;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Single-use invite links. The file stores the SHA-256 of the code, never the code.
 * Using a link sets used-at only when that field is still empty and the invite is open.
 */
@ApplicationScoped
public class InviteStore {

    static final Duration TTL = Duration.ofHours(72);
    private static final TypeReference<List<StoredInvite>> LIST = new TypeReference<>() {
    };

    private final ObjectMapper json;
    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private Clock clock = Clock.systemUTC();

    @Inject
    public InviteStore(ObjectMapper json,
                       @ConfigProperty(name = "freedriver.invites.dir", defaultValue = "data/invites") String dir) {
        this.json = json;
        this.file = Path.of(dir).resolve("invites.json");
    }

    /** Tests move expiry without waiting. */
    void clock(Clock clock) {
        this.clock = clock;
    }

    void clear() {
        synchronized (this) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new IllegalStateException("Could not clear invites", e);
            }
        }
    }

    public synchronized Created create(String name) {
        String label = label(name);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        StoredInvite row = new StoredInvite(
                UUID.randomUUID(), label, hash(code), now, now.plus(TTL), null, null);
        List<StoredInvite> rows = read();
        rows.add(row);
        write(rows);
        return new Created(view(row), code);
    }

    public synchronized List<InviteView> open() {
        Instant now = clock.instant();
        return read().stream()
                .filter(row -> row.usedAt() == null && row.cancelledAt() == null && now.isBefore(row.expiresAt()))
                .sorted(Comparator.comparing(StoredInvite::createdAt))
                .map(InviteStore::view)
                .toList();
    }

    public synchronized boolean cancel(UUID id) {
        List<StoredInvite> rows = read();
        for (int i = 0; i < rows.size(); i++) {
            StoredInvite row = rows.get(i);
            if (!row.id().equals(id) || row.usedAt() != null || row.cancelledAt() != null) {
                continue;
            }
            rows.set(i, row.cancelled(clock.instant()));
            write(rows);
            return true;
        }
        return false;
    }

    /**
     * One update. A second caller, a cancel, or an expiry all leave the row unused.
     */
    public synchronized UseResult use(String code) {
        if (code == null || code.isBlank()) {
            return UseResult.REJECTED;
        }
        byte[] presented = sha256(code);
        List<StoredInvite> rows = read();
        for (int i = 0; i < rows.size(); i++) {
            StoredInvite row = rows.get(i);
            byte[] stored = HexFormat.of().parseHex(row.codeHash());
            if (stored.length != presented.length || !MessageDigest.isEqual(stored, presented)) {
                continue;
            }
            Instant now = clock.instant();
            if (row.usedAt() != null || row.cancelledAt() != null || !now.isBefore(row.expiresAt())) {
                return UseResult.REJECTED;
            }
            rows.set(i, row.used(now));
            write(rows);
            return UseResult.USED;
        }
        return UseResult.REJECTED;
    }

    /** True when the stored file contains this code. Tests use it to prove the code is not stored. */
    public synchronized boolean fileContains(String code) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return Files.readString(file).contains(code);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read invites", e);
        }
    }

    private List<StoredInvite> read() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(json.readValue(file.toFile(), LIST));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read invites", e);
        }
    }

    private void write(List<StoredInvite> rows) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            json.writeValue(tmp.toFile(), rows);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write invites", e);
        }
    }

    static String label(String name) {
        if (name == null) {
            throw new BadInviteException();
        }
        String label = name.strip();
        if (label.isEmpty() || label.length() > 40) {
            throw new BadInviteException();
        }
        return label;
    }

    private static InviteView view(StoredInvite row) {
        return new InviteView(row.id(), row.name(), row.createdAt(), row.expiresAt());
    }

    private static String hash(String code) {
        return HexFormat.of().formatHex(sha256(code));
    }

    private static byte[] sha256(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Created(InviteView invite, String code) {
    }

    public enum UseResult {
        USED, REJECTED
    }

    record StoredInvite(
            UUID id,
            String name,
            String codeHash,
            Instant createdAt,
            Instant expiresAt,
            Instant usedAt,
            Instant cancelledAt) {

        StoredInvite used(Instant at) {
            return new StoredInvite(id, name, codeHash, createdAt, expiresAt, at, cancelledAt);
        }

        StoredInvite cancelled(Instant at) {
            return new StoredInvite(id, name, codeHash, createdAt, expiresAt, usedAt, at);
        }
    }
}
