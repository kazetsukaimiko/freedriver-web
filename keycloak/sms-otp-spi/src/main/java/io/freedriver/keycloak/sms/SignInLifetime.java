package io.freedriver.keycloak.sms;

import org.keycloak.models.RealmModel;

/**
 * How long a sign-in page stays usable: the realm's login lifespan ({@code accessCodeLifespanLogin},
 * set from {@code ACCESS_CODE_LIFESPAN_LOGIN} in {@code scripts/provision-keycloak-freedriver.sh}).
 * The phone pages receive it as {@link #ATTR} so the theme can renew a page that has been open
 * longer than half of it (#552).
 */
final class SignInLifetime {

    static final String ATTR = "freedriverSignInLifetime";

    /** Keycloak's own default for {@code accessCodeLifespanLogin}, used when the realm reports none. */
    static final int KEYCLOAK_DEFAULT_SECONDS = 1800;

    private SignInLifetime() {
    }

    static int seconds(RealmModel realm) {
        int seconds = realm.getAccessCodeLifespanLogin();
        return seconds > 0 ? seconds : KEYCLOAK_DEFAULT_SECONDS;
    }
}
