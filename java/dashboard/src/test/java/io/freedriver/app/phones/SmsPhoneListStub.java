package io.freedriver.app.phones;

import java.util.List;

/** Test base for a partial {@link SmsPhoneList}. A method the test does not override throws. */
public abstract class SmsPhoneListStub implements SmsPhoneList {

    @Override
    public List<PhoneRow> list() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(String phone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void requireSharedSecret() {
    }

    @Override
    public boolean whitelist(String phone, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean attachUsername(String phone, String username) {
        throw new UnsupportedOperationException();
    }
}
