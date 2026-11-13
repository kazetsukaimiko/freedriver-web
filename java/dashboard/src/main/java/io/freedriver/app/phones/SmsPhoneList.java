package io.freedriver.app.phones;

import java.util.List;
import java.util.UUID;

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

    /** Stores the invite agreement and texts one code. The number is not on the list yet. */
    InviteText sendInviteCode(String phone, UUID inviteId);

    /** Checks the code for that number. The attempt limit matches sign-in. */
    InviteCheck checkInviteCode(String phone, String code);

    /** @return true when the number was added */
    boolean addInvited(String phone, String name, String username);

    /**
     * Lists the number before the Keycloak user exists. Role is {@code dashboard}.
     * {@code name} may be null. @return false when the number is already listed.
     */
    boolean whitelist(String phone, String name);

    /** Stores the username created for a listed number. @return false when the row cannot take it. */
    boolean attachUsername(String phone, String username);

    enum InviteText {
        SENT, ALREADY_LISTED, LIMITED, UNAVAILABLE
    }

    enum InviteCheck {
        APPROVED, WRONG, LIMITED, UNAVAILABLE
    }
}
