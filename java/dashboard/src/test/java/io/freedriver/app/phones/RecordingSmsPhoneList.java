package io.freedriver.app.phones;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double for the sms phone list. */
@Mock
@ApplicationScoped
public class RecordingSmsPhoneList implements SmsPhoneList {

    private final List<String> removed = new CopyOnWriteArrayList<>();
    private final List<PhoneRow> rows = new CopyOnWriteArrayList<>();
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<UUID> agreements = new CopyOnWriteArrayList<>();
    private final List<PhoneRow> added = new CopyOnWriteArrayList<>();
    private final List<String> usernames = new CopyOnWriteArrayList<>();
    private InviteText sendResult = InviteText.SENT;
    private InviteCheck checkResult = InviteCheck.APPROVED;

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

    public void sendResult(InviteText result) {
        this.sendResult = result;
    }

    public void checkResult(InviteCheck result) {
        this.checkResult = result;
    }

    public List<String> sent() {
        return List.copyOf(sent);
    }

    public List<UUID> agreements() {
        return List.copyOf(agreements);
    }

    public List<PhoneRow> added() {
        return List.copyOf(added);
    }

    public List<String> usernames() {
        return List.copyOf(usernames);
    }

    @Override
    public InviteText sendInviteCode(String phone, UUID inviteId) {
        sent.add(phone);
        agreements.add(inviteId);
        return sendResult;
    }

    @Override
    public InviteCheck checkInviteCode(String phone, String code) {
        if (checkResult == InviteCheck.APPROVED && !"123456".equals(code)) {
            return InviteCheck.WRONG;
        }
        if (checkResult == InviteCheck.APPROVED && !sent.contains(phone)) {
            return InviteCheck.WRONG;
        }
        return checkResult;
    }

    @Override
    public boolean addInvited(String phone, String name, String username) {
        if (rows.stream().anyMatch(row -> row.phone().equals(phone))) {
            return false;
        }
        PhoneRow row = new PhoneRow(phone, name, "dashboard", java.time.Instant.EPOCH, null, null);
        rows.add(row);
        added.add(row);
        usernames.add(username);
        return true;
    }

    public void clear() {
        removed.clear();
        rows.clear();
        sent.clear();
        agreements.clear();
        added.clear();
        usernames.clear();
        sendResult = InviteText.SENT;
        checkResult = InviteCheck.APPROVED;
    }
}
