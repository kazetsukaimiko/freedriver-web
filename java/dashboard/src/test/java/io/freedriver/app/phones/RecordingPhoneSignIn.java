package io.freedriver.app.phones;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double for the invite service account. Records who left {@code phone-sign-in}. */
@Mock
@ApplicationScoped
public class RecordingPhoneSignIn implements PhoneSignInDirectory {

    private final List<String> removed = new CopyOnWriteArrayList<>();

    public List<String> removed() {
        return List.copyOf(removed);
    }

    @Override
    public boolean removeFromPhoneSignIn(String phone) {
        removed.add(phone);
        return true;
    }

    public void clear() {
        removed.clear();
    }
}
