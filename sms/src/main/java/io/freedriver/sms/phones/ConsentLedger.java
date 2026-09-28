package io.freedriver.sms.phones;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only log of sign-in agreements in {@code consents.jsonl} under the data directory.
 * Removing a number from the phone list leaves its records here.
 */
@ApplicationScoped
public class ConsentLedger {

    static final String FILE = "consents.jsonl";

    private final Path file;
    private final ObjectMapper json;
    private List<ConsentRecord> records;

    @Inject
    public ConsentLedger(SmsConfig config, ObjectMapper json) {
        this(config.dataDir(), json);
    }

    public ConsentLedger(Path dataDir, ObjectMapper json) {
        this.file = dataDir.resolve(FILE);
        this.json = json;
    }

    public synchronized void record(@Valid @NotNull ConsentRecord record) {
        String line;
        try {
            line = json.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Agreement record is not serializable", e);
        }
        List<ConsentRecord> current = loaded();
        JsonFiles.appendLine(file, line);
        current.add(record);
    }

    public synchronized boolean hasAgreed(String phone, ConsentPurpose purpose) {
        return loaded().stream().anyMatch(r -> r.phone().equals(phone) && r.purpose() == purpose);
    }

    public synchronized List<ConsentRecord> history(String phone) {
        return loaded().stream().filter(r -> r.phone().equals(phone)).toList();
    }

    private List<ConsentRecord> loaded() {
        if (records == null) {
            records = read();
        }
        return records;
    }

    private List<ConsentRecord> read() {
        List<ConsentRecord> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(json.readValue(line, ConsentRecord.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + FILE, e);
        }
        return out;
    }
}
