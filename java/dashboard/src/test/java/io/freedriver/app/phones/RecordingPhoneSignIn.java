package io.freedriver.app.phones;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test double for the invite service account. Records who left {@code phone-sign-in}. */
@Mock
@ApplicationScoped
public class RecordingPhoneSignIn implements PhoneSignInDirectory {

    public static final String USERNAME = "inv" + "a".repeat(32);

    private final List<String> removed = new CopyOnWriteArrayList<>();
    private final List<String> created = new CopyOnWriteArrayList<>();
    private final AtomicBoolean conflict = new AtomicBoolean();

    public List<String> removed() {
        return List.copyOf(removed);
    }

    public List<String> created() {
        return List.copyOf(created);
    }

    @Override
    public boolean removeFromPhoneSignIn(String phone) {
        removed.add(phone);
        return true;
    }

    public void conflict(boolean value) {
        conflict.set(value);
    }

    @Override
    public Created createPhoneSignInUser(String phone) {
        if (conflict.get()) {
            throw new PhoneAlreadyExists();
        }
        created.add(phone);
        return new Created(USERNAME, List.of("phone-sign-in"), List.of());
    }

    public void clear() {
        removed.clear();
        created.clear();
        conflict.set(false);
    }
}
