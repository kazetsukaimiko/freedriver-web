package io.freedriver.sms.phones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.SmsConfig;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** File record for the test profile and direct tests. Production uses {@link MongoSignInRecords}. */
@ApplicationScoped
@IfBuildProfile("test")
public class FileSignInRecords implements SignInRecords {

    private static final TypeReference<Map<String, Instant>> MAP = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper json;

    @Inject
    public FileSignInRecords(SmsConfig config, ObjectMapper json) {
        this(config.dataDir(), json);
    }

    FileSignInRecords(Path dataDir, ObjectMapper json) {
        this.file = dataDir.resolve(SignInTimes.FILE);
        this.json = json;
    }

    @Override
    public synchronized Instant get(String phone) {
        return read().get(phone);
    }

    @Override
    public synchronized void put(String phone, Instant when) {
        Map<String, Instant> next = read();
        next.put(phone, when);
        write(next);
    }

    @Override
    public synchronized void delete(String phone) {
        Map<String, Instant> next = read();
        if (next.remove(phone) != null) {
            write(next);
        }
    }

    private void write(Map<String, Instant> next) {
        try {
            JsonFiles.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + SignInTimes.FILE, e);
        }
    }

    private Map<String, Instant> read() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Instant> stored = json.readValue(file.toFile(), MAP);
            return stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SignInTimes.FILE, e);
        }
    }
}
