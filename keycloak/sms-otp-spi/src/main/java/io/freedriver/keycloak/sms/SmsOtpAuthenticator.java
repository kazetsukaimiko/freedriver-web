package io.freedriver.keycloak.sms;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Phone + code sign-in as an Alternative beside the password form.
 * With an empty or placeholder secret the execution marks itself {@code attempted}.
 * Pages render through {@code context.form()} with the freedriver login theme templates.
 * Per auth session, stored in auth notes: 4 texts (the first code and 3 resends) and
 * 5 wrong codes, after which the pending code is cleared and the phone form returns.
 * With the texts used up, both pages show the code-limit message and the phone form skips the send.
 * A verified code signs in the user only when {@link PhoneSignInPolicy} allows it.
 */
public final class SmsOtpAuthenticator implements Authenticator {

    static final String NOTE_PHONE = "freedriver.sms.phone";
    static final String NOTE_WRONG_CODES = "freedriver.sms.wrong-codes";
    static final String NOTE_SENDS = "freedriver.sms.sends";

    static final int MAX_WRONG_CODES = 5;
    static final int MAX_RESENDS = 3;

    static final String PHONE_TEMPLATE = "freedriver-sms-phone.ftl";
    static final String CODE_TEMPLATE = "freedriver-sms-code.ftl";
    static final String ATTR_RESEND_ALLOWED = "freedriverSmsResendAllowed";

    static final String MSG_PHONE_INVALID = "freedriverSmsPhoneInvalid";
    static final String MSG_UNAVAILABLE = "freedriverSmsUnavailable";
    static final String MSG_WRONG_CODE = "freedriverSmsWrongCode";
    static final String MSG_TOO_MANY_TRIES = "freedriverSmsTooManyTries";
    static final String MSG_CODE_LIMIT = "freedriverSmsCodeLimit";
    static final String MSG_DENIED = "freedriverSmsDenied";

    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    private final SmsOtpClient client;
    private final Supplier<Optional<String>> secret;

    public SmsOtpAuthenticator() {
        this(new SmsOtpClient(), SmsOtpConfig::secret);
    }

    SmsOtpAuthenticator(SmsOtpClient client, Supplier<Optional<String>> secret) {
        this.client = client;
        this.secret = secret;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (secret.get().isEmpty()) {
            context.attempted();
            return;
        }
        if (session(context).getAuthNote(NOTE_PHONE) != null) {
            context.challenge(codePage(context, null));
            return;
        }
        context.challenge(phonePage(context, sendAllowed(context) ? null : MSG_CODE_LIMIT));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        Optional<String> key = secret.get();
        if (key.isEmpty()) {
            context.attempted();
            return;
        }
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        if (form.containsKey("tryAnotherWay")) {
            clearPending(context);
            context.attempted();
            return;
        }
        String pending = session(context).getAuthNote(NOTE_PHONE);
        if (pending == null) {
            sendFirst(context, key.get(), form.getFirst("phone"));
            return;
        }
        if (form.containsKey("resend")) {
            resend(context, key.get(), pending);
            return;
        }
        verify(context, key.get(), pending, form.getFirst("code"));
    }

    private void sendFirst(AuthenticationFlowContext context, String key, String raw) {
        if (!sendAllowed(context)) {
            context.challenge(phonePage(context, MSG_CODE_LIMIT));
            return;
        }
        String phone = PhoneSignInPolicy.normalizePhone(raw);
        if (!PhoneSignInPolicy.validPhone(phone)) {
            context.challenge(phonePage(context, MSG_PHONE_INVALID));
            return;
        }
        if (text(context, key, phone) != SmsOtpClient.Outcome.SENT) {
            context.challenge(phonePage(context, MSG_UNAVAILABLE));
            return;
        }
        session(context).setAuthNote(NOTE_PHONE, phone);
        session(context).removeAuthNote(NOTE_WRONG_CODES);
        context.challenge(codePage(context, null));
    }

    private void resend(AuthenticationFlowContext context, String key, String phone) {
        if (!sendAllowed(context)) {
            context.challenge(codePage(context, null));
            return;
        }
        if (text(context, key, phone) != SmsOtpClient.Outcome.SENT) {
            context.challenge(codePage(context, MSG_UNAVAILABLE));
            return;
        }
        context.challenge(codePage(context, null));
    }

    /** Counts the send in the auth session, then asks sms to text the code. */
    private SmsOtpClient.Outcome text(AuthenticationFlowContext context, String key, String phone) {
        session(context).setAuthNote(NOTE_SENDS, Integer.toString(count(context, NOTE_SENDS) + 1));
        return client.send(key, phone).outcome();
    }

    private void verify(AuthenticationFlowContext context, String key, String phone, String raw) {
        String code = raw == null ? "" : raw.trim();
        if (!CODE.matcher(code).matches()) {
            wrongCode(context);
            return;
        }
        SmsOtpClient.Result result = client.verify(key, phone, code);
        switch (result.outcome()) {
            case VERIFIED -> signIn(context, phone, result.username());
            case INVALID_CODE -> wrongCode(context);
            default -> context.challenge(codePage(context, MSG_UNAVAILABLE));
        }
    }

    private void wrongCode(AuthenticationFlowContext context) {
        int wrong = count(context, NOTE_WRONG_CODES) + 1;
        if (wrong >= MAX_WRONG_CODES) {
            clearPending(context);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    phonePage(context, sendAllowed(context) ? MSG_TOO_MANY_TRIES : MSG_CODE_LIMIT));
            return;
        }
        session(context).setAuthNote(NOTE_WRONG_CODES, Integer.toString(wrong));
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, codePage(context, MSG_WRONG_CODE));
    }

    private void signIn(AuthenticationFlowContext context, String phone, String username) {
        UserModel user;
        try {
            user = context.getSession().users().getUserByUsername(context.getRealm(), username);
        } catch (RuntimeException ex) {
            user = null;
        }
        clearPending(context);
        if (!PhoneSignInPolicy.allows(user, phone)) {
            context.failureChallenge(AuthenticationFlowError.ACCESS_DENIED, phonePage(context, MSG_DENIED));
            return;
        }
        context.setUser(user);
        context.success();
    }

    private static boolean sendAllowed(AuthenticationFlowContext context) {
        return count(context, NOTE_SENDS) < 1 + MAX_RESENDS;
    }

    /** Integer auth note; a malformed value reads as the maximum, which closes the cap. */
    static int count(AuthenticationFlowContext context, String note) {
        String value = session(context).getAuthNote(note);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE - 1;
        }
    }

    /** Clears the pending phone and its wrong-code count. The send count stays for the auth session. */
    private static void clearPending(AuthenticationFlowContext context) {
        session(context).removeAuthNote(NOTE_PHONE);
        session(context).removeAuthNote(NOTE_WRONG_CODES);
    }

    private static AuthenticationSessionModel session(AuthenticationFlowContext context) {
        return context.getAuthenticationSession();
    }

    private static Response phonePage(AuthenticationFlowContext context, String error) {
        LoginFormsProvider form = context.form();
        if (error != null) {
            form.setError(error);
        }
        return form.createForm(PHONE_TEMPLATE);
    }

    private static Response codePage(AuthenticationFlowContext context, String error) {
        LoginFormsProvider form = context.form().setAttribute(ATTR_RESEND_ALLOWED, sendAllowed(context));
        if (error != null) {
            form.setError(error);
        }
        return form.createForm(CODE_TEMPLATE);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return secret.get().isPresent();
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Number hand-out lives in portal-admin on app.freedriver.io (#107).
    }

    @Override
    public void close() {}
}
