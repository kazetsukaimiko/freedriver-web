package io.freedriver.sms.phones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The phone list in {@code phones.json} under the data directory: the only numbers that can get a code. */
@ApplicationScoped
public class PhoneDirectory {

    static final String FILE = "phones.json";
    private static final TypeReference<List<PhoneEntry>> LIST = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper json;
    private Map<String, PhoneEntry> entries;

    @Inject
    public PhoneDirectory(SmsConfig config, ObjectMapper json) {
        this(config.dataDir(), json);
    }

    public PhoneDirectory(Path dataDir, ObjectMapper json) {
        this.file = dataDir.resolve(FILE);
        this.json = json;
    }

    public synchronized Optional<PhoneEntry> find(String phone) {
        return Optional.ofNullable(loaded().get(phone));
    }

    public synchronized List<PhoneEntry> list() {
        return List.copyOf(loaded().values());
    }

    /** Adds the number; false when it is already on the list. */
    public synchronized boolean add(@Valid @NotNull PhoneEntry entry) {
        if (loaded().containsKey(entry.phone())) {
            return false;
        }
        Map<String, PhoneEntry> next = new LinkedHashMap<>(loaded());
        next.put(entry.phone(), entry);
        write(next);
        return true;
    }

    /** Takes the number off the list. Its agreement records stay in the {@link ConsentLedger}. */
    public synchronized boolean remove(String phone) {
        if (!loaded().containsKey(phone)) {
            return false;
        }
        Map<String, PhoneEntry> next = new LinkedHashMap<>(loaded());
        next.remove(phone);
        write(next);
        return true;
    }

    private void write(Map<String, PhoneEntry> next) {
        try {
            JsonFiles.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(next.values()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + FILE, e);
        }
        entries = next;
    }

    private Map<String, PhoneEntry> loaded() {
        if (entries == null) {
            entries = read();
        }
        return entries;
    }

    private Map<String, PhoneEntry> read() {
        Map<String, PhoneEntry> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (PhoneEntry entry : json.readValue(file.toFile(), LIST)) {
                out.put(entry.phone(), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + FILE, e);
        }
        return out;
    }
}
