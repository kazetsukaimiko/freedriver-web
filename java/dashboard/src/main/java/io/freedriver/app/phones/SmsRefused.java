package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;

/** sms answered with a status outside 2xx. The status tells the invite answers apart. */
public class SmsRefused extends InviteUpstreamException {

    private final int status;

    public SmsRefused(int status) {
        super("sms answered " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
