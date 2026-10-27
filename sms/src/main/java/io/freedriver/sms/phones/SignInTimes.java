package io.freedriver.sms.phones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When a listed number last accepted a sign-in code. This is not the agreement log.
 * Removing the number forgets the time, so a later invite can show the login link again.
 */
@ApplicationScoped
public class SignInTimes {

    static final String FILE = "sign-ins.json";
    private static final TypeReference<Map<String, Instant>> MAP = new TypeReference<>() {
    };

    private final Path file;
    private final ObjectMapper json;
    private final Clock clock;
    private Map<String, Instant> times;

    @Inject
    public SignInTimes(SmsConfig config, ObjectMapper json) {
        this(config.dataDir(), json, Clock.systemUTC());
    }

    SignInTimes(Path dataDir, ObjectMapper json, Clock clock) {
        this.file = dataDir.resolve(FILE);
        this.json = json;
        this.clock = clock;
    }

    public synchronized Instant last(String phone) {
        return loaded().get(phone);
    }

    /** Records this sign-in. A later sign-in replaces the time. */
    public synchronized Instant record(String phone) {
        if (phone == null || !phone.matches("\\+1[0-9]{10}")) {
            throw new IllegalArgumentException("phone");
        }
        Instant when = clock.instant();
        Map<String, Instant> next = new LinkedHashMap<>(loaded());
        next.put(phone, when);
        write(next);
        return when;
    }

    public synchronized void forget(String phone) {
        if (phone == null || !loaded().containsKey(phone)) {
            return;
        }
        Map<String, Instant> next = new LinkedHashMap<>(loaded());
        next.remove(phone);
        write(next);
    }

    private void write(Map<String, Instant> next) {
        try {
            JsonFiles.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + FILE, e);
        }
        times = next;
    }

    private Map<String, Instant> loaded() {
        if (times == null) {
            times = read();
        }
        return times;
    }

    private Map<String, Instant> read() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Instant> stored = json.readValue(file.toFile(), MAP);
            return stored == null ? new LinkedHashMap<>() : new LinkedHashMap<>(stored);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + FILE, e);
        }
    }
}
