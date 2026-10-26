package io.freedriver.sms.invite;

import io.freedriver.sms.otp.SendOutcome;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory invitation sender for dev and tests. It records each text and makes no network call. */
public class FakeInvitationSender implements InvitationSender {

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean configured = true;

    @Override
    public boolean configured() {
        return configured;
    }

    public void configured(boolean configured) {
        this.configured = configured;
    }

    @Override
    public SendOutcome send(String phone, String body) {
        sent.add(new Sent(phone, body));
        return SendOutcome.SENT;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
        configured = true;
    }

    public record Sent(String phone, String body) {
    }
}
