package io.freedriver.app.phones;

import java.util.List;

/** The sms phone list. sms does not call Keycloak. */
public interface SmsPhoneList {

    List<PhoneRow> list();

    /** @return true when the number was on the list */
    boolean remove(String phone);

    /**
     * Throws when the shared secret is null, empty, or whitespace.
     * Does not delete a row and does not open a connection.
     */
    void requireSharedSecret();
}
