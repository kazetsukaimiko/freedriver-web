package io.freedriver.keycloak.sms;

import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * First sign-in field: a phone number or a username. Sets
 * {@code freedriver.sms.route} and, for a phone, {@code freedriver.sms.number},
 * then calls {@code success()} without {@code setUser}. Does not call the SMS
 * client and does not set {@code freedriver.sms.phone}. An empty or placeholder
 * {@code SMS_OTP_SHARED_SECRET} forces the username route, so no text is sent.
 * This provider is not attached to the live realm flow.
 */
public final class PhoneUsernameRouter implements Authenticator {

    static final String NOTE_ROUTE = "freedriver.sms.route";
    static final String NOTE_NUMBER = "freedriver.sms.number";
    static final String ROUTE_PHONE = "phone";
    static final String ROUTE_USERNAME = "username";

    static final String TEMPLATE = "freedriver-phone-username.ftl";
    static final String FIELD = "username";

    static final String USERNAME_LOG = "sms route username";

    private static final Logger LOG = Logger.getLogger(PhoneUsernameRouter.class.getName());

    private final Supplier<Optional<String>> secret;

    public PhoneUsernameRouter() {
        this(SmsOtpConfig::secret);
    }

    PhoneUsernameRouter(Supplier<Optional<String>> secret) {
        this.secret = secret;
    }

    /** Last four digits only. The full number is not part of the message. */
    static String phoneLog(String canonical) {
        return "sms route phone " + canonical.substring(canonical.length() - 4);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        context.challenge(context.form()
                .setAttribute(SmsOtpAuthenticator.ATTR_AGREEMENT, SignInAgreement.WORDING)
                .createForm(TEMPLATE));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        String raw = form == null ? null : form.getFirst(FIELD);
        AuthenticationSessionModel session = context.getAuthenticationSession();
        // A stale pending phone would make freedriver-sms-otp skip the send.
        session.removeAuthNote(SmsOtpAuthenticator.NOTE_PHONE);
        Optional<String> number = secret.get().isPresent() ? UsPhoneNumbers.canonical(raw) : Optional.empty();
        if (number.isPresent()) {
            String canonical = number.get();
            session.setAuthNote(NOTE_ROUTE, ROUTE_PHONE);
            session.setAuthNote(NOTE_NUMBER, canonical);
            LOG.info(phoneLog(canonical));
        } else {
            session.setAuthNote(NOTE_ROUTE, ROUTE_USERNAME);
            session.removeAuthNote(NOTE_NUMBER);
            LOG.info(USERNAME_LOG);
        }
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
