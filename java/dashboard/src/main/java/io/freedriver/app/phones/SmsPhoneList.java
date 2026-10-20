package io.freedriver.app.phones;

import java.util.List;

/** The sms phone list. sms does not call Keycloak. */
public interface SmsPhoneList {

    List<PhoneRow> list();

    /** @return true when the number was on the list */
    boolean remove(String phone);
}
