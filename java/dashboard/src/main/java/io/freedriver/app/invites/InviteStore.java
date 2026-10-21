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
import java.util.Optional;
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
                UUID.randomUUID(), label, hash(code), now, now.plus(TTL), null, null, 0, 0, null);
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
        int index = indexOf(code);
        if (index < 0) {
            return UseResult.REJECTED;
        }
        List<StoredInvite> rows = read();
        StoredInvite row = rows.get(index);
        if (!open(row)) {
            return UseResult.REJECTED;
        }
        rows.set(index, row.used(clock.instant()));
        write(rows);
        return UseResult.USED;
    }

    /** The invite behind this code, or empty when the code matches nothing. */
    public synchronized Optional<InviteState> state(String code) {
        int index = indexOf(code);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(stateOf(read().get(index)));
    }

    /**
     * Counts one number submission and one code. A new number replaces the pending number,
     * which cancels the earlier code for this invite.
     */
    public synchronized Slot takeSubmission(String code, String phone) {
        int index = indexOf(code);
        if (index < 0) {
            return Slot.DEAD;
        }
        List<StoredInvite> rows = read();
        StoredInvite row = rows.get(index);
        if (!open(row) || row.submissionCount() >= 3) {
            return Slot.DEAD;
        }
        if (row.codeCount() >= 3) {
            return Slot.CODES;
        }
        rows.set(index, row.pending(phone, row.submissionCount() + 1, row.codeCount() + 1));
        write(rows);
        return Slot.OK;
    }

    /** Counts a resend to the number already pending. Not a new submission. */
    public synchronized Slot takeResend(String code) {
        int index = indexOf(code);
        if (index < 0) {
            return Slot.DEAD;
        }
        List<StoredInvite> rows = read();
        StoredInvite row = rows.get(index);
        if (!open(row) || row.pendingPhone() == null) {
            return Slot.DEAD;
        }
        if (row.codeCount() >= 3) {
            return Slot.CODES;
        }
        rows.set(index, row.pending(row.pendingPhone(), row.submissionCount(), row.codeCount() + 1));
        write(rows);
        return Slot.OK;
    }

    /** Puts the counts back when the text was not sent. Does nothing if a later update won. */
    public synchronized void restore(String code, InviteState previous) {
        int index = indexOf(code);
        if (index < 0 || previous == null) {
            return;
        }
        List<StoredInvite> rows = read();
        StoredInvite row = rows.get(index);
        boolean submission = row.submissionCount() == previous.submissions() + 1
                && row.codeCount() == previous.codesSent() + 1;
        boolean resend = row.submissionCount() == previous.submissions()
                && row.codeCount() == previous.codesSent() + 1
                && previous.pendingPhone() != null
                && previous.pendingPhone().equals(row.pendingPhone());
        if (!submission && !resend) {
            return;
        }
        rows.set(index, row.pending(previous.pendingPhone(), previous.submissions(), previous.codesSent()));
        write(rows);
    }

    /** Rate-limiter key. The code itself is not the key. */
    public static String rateKey(String code) {
        if (code == null || code.isBlank()) {
            return "invite-redeem";
        }
        return "invite-redeem:" + hash(code);
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

    private int indexOf(String code) {
        if (code == null || code.isBlank()) {
            return -1;
        }
        byte[] presented = sha256(code);
        List<StoredInvite> rows = read();
        for (int i = 0; i < rows.size(); i++) {
            byte[] stored = HexFormat.of().parseHex(rows.get(i).codeHash());
            if (stored.length == presented.length && MessageDigest.isEqual(stored, presented)) {
                return i;
            }
        }
        return -1;
    }

    private boolean open(StoredInvite row) {
        return row.usedAt() == null && row.cancelledAt() == null && clock.instant().isBefore(row.expiresAt());
    }

    private InviteState stateOf(StoredInvite row) {
        return new InviteState(row.id(), row.name(), row.pendingPhone(), row.submissionCount(), row.codeCount(), open(row));
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

    public enum Slot {
        OK, DEAD, CODES
    }

    public record InviteState(UUID id, String name, String pendingPhone, int submissions, int codesSent, boolean open) {
    }

    record StoredInvite(
            UUID id,
            String name,
            String codeHash,
            Instant createdAt,
            Instant expiresAt,
            Instant usedAt,
            Instant cancelledAt,
            Integer submissions,
            Integer codesSent,
            String pendingPhone) {

        int submissionCount() {
            return submissions == null ? 0 : submissions;
        }

        int codeCount() {
            return codesSent == null ? 0 : codesSent;
        }

        StoredInvite used(Instant at) {
            return new StoredInvite(id, name, codeHash, createdAt, expiresAt, at, cancelledAt,
                    submissionCount(), codeCount(), pendingPhone);
        }

        StoredInvite cancelled(Instant at) {
            return new StoredInvite(id, name, codeHash, createdAt, expiresAt, usedAt, at,
                    submissionCount(), codeCount(), pendingPhone);
        }

        StoredInvite pending(String phone, int submissions, int codesSent) {
            return new StoredInvite(id, name, codeHash, createdAt, expiresAt, usedAt, cancelledAt,
                    submissions, codesSent, phone);
        }
    }
}
