package io.freedriver.app.phones;

/** Test {@link PhoneSignInDirectory} that only removes. Creating a user throws. */
@FunctionalInterface
public interface RemovalOnly {

    boolean remove(String phone);

    static PhoneSignInDirectory of(RemovalOnly removal) {
        return new PhoneSignInDirectory() {
            @Override
            public boolean removeFromPhoneSignIn(String phone) {
                return removal.remove(phone);
            }

            @Override
            public Created createPhoneSignInUser(String phone) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
