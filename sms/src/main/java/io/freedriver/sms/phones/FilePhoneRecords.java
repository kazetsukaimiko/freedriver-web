package io.freedriver.sms.phones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.SmsConfig;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File allowlist for the test profile and direct tests. Production uses {@link MongoPhoneRecords}.
 * The test build has both beans; this alternative is the {@link PhoneRecords} there.
 */
@Alternative
@Priority(1)
@ApplicationScoped
@IfBuildProfile("test")
public class FilePhoneRecords implements PhoneRecords {

    private static final TypeReference<List<PhoneEntry>> LIST = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper json;

    @Inject
    public FilePhoneRecords(SmsConfig config, ObjectMapper json) {
        this(config.dataDir(), json);
    }

    FilePhoneRecords(Path dataDir, ObjectMapper json) {
        this.file = dataDir.resolve(PhoneDirectory.FILE);
        this.json = json;
    }

    @Override
    public synchronized Optional<PhoneEntry> find(String phone) {
        return Optional.ofNullable(read().get(phone));
    }

    @Override
    public synchronized List<PhoneEntry> list() {
        return List.copyOf(read().values());
    }

    @Override
    public synchronized boolean insert(PhoneEntry entry) {
        Map<String, PhoneEntry> next = read();
        if (next.containsKey(entry.phone())) {
            return false;
        }
        next.put(entry.phone(), entry);
        write(next);
        return true;
    }

    @Override
    public synchronized boolean replace(PhoneEntry entry) {
        Map<String, PhoneEntry> next = read();
        if (!next.containsKey(entry.phone())) {
            return false;
        }
        next.put(entry.phone(), entry);
        write(next);
        return true;
    }

    @Override
    public synchronized boolean delete(String phone) {
        Map<String, PhoneEntry> next = read();
        if (next.remove(phone) == null) {
            return false;
        }
        write(next);
        return true;
    }

    private void write(Map<String, PhoneEntry> next) {
        try {
            JsonFiles.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(next.values()));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + PhoneDirectory.FILE, e);
        }
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
            throw new UncheckedIOException("Could not read " + PhoneDirectory.FILE, e);
        }
        return out;
    }
}
