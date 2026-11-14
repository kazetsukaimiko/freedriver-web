package io.freedriver.sms.phones;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Sorts;
import io.freedriver.sms.SmsConfig;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Sign-in agreements in {@code consents}, the legal record. Insert only. Removing a number from the
 * phone list leaves its records. {@code consents.jsonl} in the data directory is the archive of the
 * agreements before this collection, imported once.
 */
@ApplicationScoped
public class ConsentLedger implements PanacheMongoRepositoryBase<Consent, String> {

    static final String FILE = "consents.jsonl";
    private static final Logger LOG = Logger.getLogger(ConsentLedger.class);

    @Inject
    SmsConfig config;

    @Inject
    ObjectMapper json;

    public void record(@Valid @NotNull ConsentRecord record) {
        persist(Consent.of(UUID.randomUUID().toString(), record));
    }

    /** A count on the {@code {phone, purpose}} index. */
    public boolean hasAgreed(String phone, ConsentPurpose purpose) {
        return mongoCollection().countDocuments(and(eq("phone", phone), eq("purpose", purpose.name()))) > 0;
    }

    /** The number's agreements, oldest first. */
    public List<ConsentRecord> history(String phone) {
        return mongoCollection().find(eq("phone", phone)).sort(Sorts.ascending("givenAt")).into(new ArrayList<>())
                .stream().map(Consent::record).toList();
    }

    /** At startup, takes the archive file when it exists and is not fully imported yet. The file stays. */
    void importArchive(@Observes StartupEvent start) {
        importArchive(config.dataDir().resolve(FILE));
    }

    /**
     * Imports the archive when it exists and its last line is not stored. Lines go in file order,
     * so a stored last line means an earlier start finished; a start that stopped part way resumes.
     * The log line names the file's absolute path and SHA-256.
     *
     * @return the rows inserted
     */
    int importArchive(Path file) {
        if (!Files.exists(file)) {
            return 0;
        }
        List<Consent> rows = parse(file);
        if (rows.isEmpty() || findById(rows.getLast().id) != null) {
            return 0;
        }
        int inserted = insert(rows);
        LOG.infof("Imported %d agreements from %s sha256=%s", inserted, file.toAbsolutePath(), sha256(file));
        return inserted;
    }

    /**
     * Inserts each line under its line number as {@code _id}, so a second import of the same file
     * inserts nothing. Blank lines are skipped and keep their number. The whole file is parsed before
     * the first insert.
     *
     * @return the rows inserted
     */
    int importFile(Path file) {
        return insert(parse(file));
    }

    private List<Consent> parse(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + FILE, e);
        }
        List<Consent> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            try {
                rows.add(Consent.of(Integer.toString(i + 1), json.readValue(lines.get(i), ConsentRecord.class)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read line " + (i + 1) + " of " + FILE, e);
            }
        }
        return rows;
    }

    private int insert(List<Consent> rows) {
        int inserted = 0;
        for (Consent row : rows) {
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
}
