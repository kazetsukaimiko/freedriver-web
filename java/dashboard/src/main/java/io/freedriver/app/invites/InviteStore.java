package io.freedriver.app.invites;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.freedriver.app.metrics.BlockingIo;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.not;
import static com.mongodb.client.model.Filters.or;

/**
 * Single-use invite links in {@code invites}. Each change is one filtered update, so two replicas
 * spend a link once. {@code invites.json} in the invites directory is the archive of the links
 * before this collection, imported once.
 */
@BlockingIo
@ApplicationScoped
public class InviteStore implements PanacheMongoRepositoryBase<Invite, String> {

    static final Duration TTL = Duration.ofHours(72);
    static final String FILE = "invites.json";
    private static final int CAP = 3;
    private static final Logger LOG = Logger.getLogger(InviteStore.class);
    private static final TypeReference<List<ArchivedInvite>> ARCHIVE = new TypeReference<>() {
    };

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "freedriver.invites.dir", defaultValue = "data/invites")
    String dir;

    private final SecureRandom random = new SecureRandom();
    private Clock clock = Clock.systemUTC();

    /** Tests move expiry without waiting. */
    void clock(Clock clock) {
        this.clock = clock;
    }

    public Created create(String name) {
        String label = label(name);
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Invite row = new Invite();
        row.id = UUID.randomUUID().toString();
        row.name = label;
        row.codeHash = hash(code);
        row.createdAt = now;
        row.expiresAt = now.plus(TTL);
        row.submissions = 0;
        row.codesSent = 0;
        persist(row);
        return new Created(view(row), code);
    }

    public List<InviteView> open() {
        Date now = Date.from(clock.instant());
        return mongoCollection()
                .find(and(eq("usedAt", null), eq("cancelledAt", null), gt("expiresAt", now)))
                .sort(Sorts.ascending("createdAt"))
                .into(new ArrayList<>())
                .stream().map(InviteStore::view).toList();
    }

    public boolean cancel(UUID id) {
        return mongoCollection().updateOne(
                and(eq("_id", id.toString()), eq("usedAt", null), eq("cancelledAt", null)),
                Updates.set("cancelledAt", Date.from(clock.instant()))).getModifiedCount() == 1;
    }

    /** One {@code findOneAndUpdate}. A second caller, a cancel, or an expiry all leave the row unused. */
    public UseResult use(String code) {
        if (blank(code)) {
            return UseResult.REJECTED;
        }
        Invite used = mongoCollection().findOneAndUpdate(
                open(hash(code)),
                Updates.set("usedAt", Date.from(clock.instant())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return used == null ? UseResult.REJECTED : UseResult.USED;
    }

    /** The invite behind this code, or empty when the code matches nothing. */
    public Optional<InviteState> state(String code) {
        return find(code).map(this::stateOf);
    }

    /**
     * Counts one number submission and one code in one filtered update. A new number replaces the
     * pending number, which cancels the earlier code for this invite.
     */
    public Slot takeSubmission(String code, String phone) {
        if (blank(code)) {
            return Slot.DEAD;
        }
        Invite taken = mongoCollection().findOneAndUpdate(
                and(open(hash(code)), under("submissions"), under("codesSent")),
                Updates.combine(Updates.set("pendingPhone", phone), Updates.inc("submissions", 1),
                        Updates.inc("codesSent", 1)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (taken != null) {
            return Slot.OK;
        }
        Invite row = find(code).orElse(null);
        if (row == null || !open(row) || row.submissionCount() >= CAP) {
            return Slot.DEAD;
        }
        return row.codeCount() >= CAP ? Slot.CODES : Slot.DEAD;
    }

    /** Counts a resend to the number already pending, in one filtered update. Not a new submission. */
    public Slot takeResend(String code) {
        if (blank(code)) {
            return Slot.DEAD;
        }
        Invite taken = mongoCollection().findOneAndUpdate(
                and(open(hash(code)), ne("pendingPhone", null), under("codesSent")),
                Updates.inc("codesSent", 1),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (taken != null) {
            return Slot.OK;
        }
        Invite row = find(code).orElse(null);
        if (row == null || !open(row) || row.pendingPhone == null) {
            return Slot.DEAD;
        }
        return row.codeCount() >= CAP ? Slot.CODES : Slot.DEAD;
    }

    /**
     * Puts the counts back when the text was not sent. Filtered on the counts the take left, so it
     * does nothing if a later update won.
     */
    public void restore(String code, InviteState previous) {
        if (blank(code) || previous == null) {
            return;
        }
        String codeHash = hash(code);
        Bson back = Updates.combine(Updates.set("pendingPhone", previous.pendingPhone()),
                Updates.set("submissions", previous.submissions()), Updates.set("codesSent", previous.codesSent()));
        boolean submission = mongoCollection().updateOne(
                and(eq("codeHash", codeHash), count("submissions", previous.submissions() + 1),
                        count("codesSent", previous.codesSent() + 1)),
                back).getMatchedCount() == 1;
        if (!submission && previous.pendingPhone() != null) {
            mongoCollection().updateOne(
                    and(eq("codeHash", codeHash), count("submissions", previous.submissions()),
                            count("codesSent", previous.codesSent() + 1), eq("pendingPhone", previous.pendingPhone())),
                    back);
        }
    }

    /** Rate-limiter key. The code itself is not the key. */
    public static String rateKey(String code) {
        if (blank(code)) {
            return "invite-redeem";
        }
        return "invite-redeem:" + hash(code);
    }

    /** At startup, takes the archive file when it exists and is not fully imported yet. The file stays. */
    void importArchive(@Observes StartupEvent start) {
        importArchive(Path.of(dir).resolve(FILE));
    }

    /**
     * Imports the archive when it exists and its last invite is not stored. Rows go in file order,
     * so a stored last row means an earlier start finished; a start that stopped part way resumes.
     * The log line names the file's absolute path and SHA-256.
     *
     * @return the rows inserted
     */
    int importArchive(Path file) {
        if (!Files.exists(file)) {
            return 0;
        }
        List<Invite> rows = parse(file);
        if (rows.isEmpty() || findById(rows.getLast().id) != null) {
            return 0;
        }
        int inserted = insert(rows);
        LOG.infof("Imported %d invites from %s sha256=%s", inserted, file.toAbsolutePath(), sha256(file));
        return inserted;
    }

    /**
     * Inserts each archived invite under its own id, so a second import of the same file inserts
     * nothing. The whole file is parsed before the first insert.
     *
     * @return the rows inserted
     */
    int importFile(Path file) {
        return insert(parse(file));
    }

    private List<Invite> parse(Path file) {
        try {
            List<ArchivedInvite> rows = json.readValue(Files.readString(file, StandardCharsets.UTF_8), ARCHIVE);
            return rows.stream().map(ArchivedInvite::invite).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + FILE, e);
        }
    }

    private int insert(List<Invite> rows) {
        int inserted = 0;
        for (Invite row : rows) {
            try {
                persist(row);
                inserted++;
            } catch (MongoWriteException ex) {
                if (ex.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw ex;
                }
            }
        }
        return inserted;
    }

    /** Hex SHA-256 of the file, logged with each import so the imported archive can be identified later. */
    private static String sha256(Path file) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            return "unreadable";
        }
    }

    private Optional<Invite> find(String code) {
        if (blank(code)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mongoCollection().find(eq("codeHash", hash(code))).first());
    }

    /** The link still works: unused, not cancelled, not expired. */
    private Bson open(String codeHash) {
        return and(eq("codeHash", codeHash), eq("usedAt", null), eq("cancelledAt", null),
                gt("expiresAt", Date.from(clock.instant())));
    }

    /** Under the cap. A missing count, from an older invite, is zero. */
    private static Bson under(String field) {
        return not(gte(field, CAP));
    }

    /** Exactly {@code n}. A missing count, from an older invite, is zero. */
    private static Bson count(String field, int n) {
        return n == 0 ? or(eq(field, 0), exists(field, false)) : eq(field, n);
    }

    private boolean open(Invite row) {
        return row.usedAt == null && row.cancelledAt == null && clock.instant().isBefore(row.expiresAt);
    }

    private InviteState stateOf(Invite row) {
        return new InviteState(UUID.fromString(row.id), row.name, row.pendingPhone, row.submissionCount(),
                row.codeCount(), open(row));
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

    private static boolean blank(String code) {
        return code == null || code.isBlank();
    }

    private static InviteView view(Invite row) {
        return new InviteView(UUID.fromString(row.id), row.name, row.createdAt, row.expiresAt);
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
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

    /** One row of {@code invites.json}, as the file stored it. */
    record ArchivedInvite(
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

        Invite invite() {
            Invite row = new Invite();
            row.id = id.toString();
            row.name = name;
            row.codeHash = codeHash;
            row.createdAt = createdAt;
            row.expiresAt = expiresAt;
            row.usedAt = usedAt;
            row.cancelledAt = cancelledAt;
            row.submissions = submissions;
            row.codesSent = codesSent;
            row.pendingPhone = pendingPhone;
            return row;
        }
    }
}
