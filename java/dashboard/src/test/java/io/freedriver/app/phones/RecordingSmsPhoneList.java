package io.freedriver.app.phones;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double for the sms phone list. */
@Mock
@ApplicationScoped
public class RecordingSmsPhoneList implements SmsPhoneList {

    private final List<String> removed = new CopyOnWriteArrayList<>();
    private final List<PhoneRow> rows = new CopyOnWriteArrayList<>();

    public void add(PhoneRow row) {
        rows.add(row);
    }

    public List<String> removed() {
        return List.copyOf(removed);
    }

    @Override
    public List<PhoneRow> list() {
        return List.copyOf(rows);
    }

    @Override
    public boolean remove(String phone) {
        removed.add(phone);
        return rows.removeIf(row -> row.phone().equals(phone));
    }

    @Override
    public void requireSharedSecret() {
    }

    public void clear() {
        removed.clear();
        rows.clear();
    }
}
