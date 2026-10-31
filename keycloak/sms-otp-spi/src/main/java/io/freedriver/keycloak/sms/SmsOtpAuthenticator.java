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

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Phone + code sign-in as an Alternative beside the password form.
 * With an empty or placeholder secret the execution marks itself {@code attempted}.
 * Pages render through {@code context.form()} with the freedriver login theme templates.
 * Per auth session, stored in auth notes: 4 texts (the first code and 3 resends).
 * An expired code and too many wrong codes stay on the code form with the field cleared.
 * With the texts used up, both pages show the code-limit message and the phone form skips the send.
 * A verified code signs in only when exactly one user with that {@code phone} attribute
 * passes {@link PhoneSignInPolicy}. Any other outcome is a wrong code.
 * When auth note {@code freedriver.sms.route} is {@code phone}, the number in
 * {@code freedriver.sms.number} uses this same send path, including the send cap.
 * An empty or placeholder secret still marks the execution attempted before any send.
 */
public final class SmsOtpAuthenticator implements Authenticator {

    static final String NOTE_PHONE = "freedriver.sms.phone";
    static final String NOTE_WRONG_CODES = "freedriver.sms.wrong-codes";
    static final String NOTE_SENDS = "freedriver.sms.sends";

    static final int MAX_RESENDS = 3;

    static final String PHONE_TEMPLATE = "freedriver-sms-phone.ftl";
    static final String CODE_TEMPLATE = "freedriver-sms-code.ftl";
    static final String ATTR_RESEND_ALLOWED = "freedriverSmsResendAllowed";
    static final String ATTR_AGREEMENT = "freedriverSmsAgreement";
    static final String ATTR_CODE = "freedriverSmsCode";

    static final String MSG_PHONE_INVALID = "freedriverSmsPhoneInvalid";
    static final String MSG_UNAVAILABLE = "freedriverSmsUnavailable";
    static final String MSG_WRONG_CODE = "freedriverSmsWrongCode";
    static final String MSG_EXPIRED = "freedriverSmsExpired";
    static final String MSG_TOO_MANY_CODES = "freedriverSmsTooManyCodes";
    static final String MSG_CODE_LIMIT = "freedriverSmsCodeLimit";
    static final String MSG_RATE_LIMITED = "freedriverSmsRateLimited";
    static final String MSG_RATE_LIMITED_ONE = "freedriverSmsRateLimitedOne";
    static final String MSG_CHECK_UNAVAILABLE = "freedriverSmsCheckUnavailable";

    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    private final SmsOtpCalls client;
    private final Supplier<Optional<String>> secret;

    public SmsOtpAuthenticator() {
        this(SmsOtpCalls.production(), SmsOtpConfig::secret);
    }

    SmsOtpAuthenticator(SmsOtpCalls client, Supplier<Optional<String>> secret) {
        this.client = client;
        this.secret = secret;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        Optional<String> key = secret.get();
        if (key.isEmpty()) {
            context.attempted();
            return;
        }
        if (session(context).getAuthNote(NOTE_PHONE) != null) {
            context.challenge(codePage(context, null, null));
            return;
        }
        if (PhoneUsernameRouter.ROUTE_PHONE.equals(session(context).getAuthNote(PhoneUsernameRouter.NOTE_ROUTE))) {
            sendFirst(context, key.get(), session(context).getAuthNote(PhoneUsernameRouter.NOTE_NUMBER));
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
        SmsOtpCalls.Result result = text(context, key, phone, false);
        if (result.outcome() == SmsOtpCalls.Outcome.RATE_LIMITED) {
            context.challenge(rateLimited(context, true, result.retryAfterSeconds()));
            return;
        }
        if (result.outcome() != SmsOtpCalls.Outcome.SENT || !phone.equals(result.phone())) {
            context.challenge(phonePage(context, MSG_UNAVAILABLE));
            return;
        }
        session(context).setAuthNote(NOTE_PHONE, phone);
        session(context).removeAuthNote(NOTE_WRONG_CODES);
        context.challenge(codePage(context, null, null));
    }

    private void resend(AuthenticationFlowContext context, String key, String phone) {
        if (!sendAllowed(context)) {
            context.challenge(codePage(context, null, null));
            return;
        }
        SmsOtpCalls.Result result = text(context, key, phone, true);
        if (result.outcome() == SmsOtpCalls.Outcome.RATE_LIMITED) {
            context.challenge(rateLimited(context, false, result.retryAfterSeconds()));
            return;
        }
        if (result.outcome() != SmsOtpCalls.Outcome.SENT || !phone.equals(result.phone())) {
            context.challenge(codePage(context, MSG_UNAVAILABLE, null));
            return;
        }
        context.challenge(codePage(context, null, null));
    }

    /** Counts the send in the auth session, then asks sms to text the code. */
    private SmsOtpCalls.Result text(AuthenticationFlowContext context, String key, String phone, boolean resend) {
        session(context).setAuthNote(NOTE_SENDS, Integer.toString(count(context, NOTE_SENDS) + 1));
        String cid = SmsOtpLog.newCorrelationId();
        SmsOtpCalls.Result result = client.send(key, phone, cid);
        SmsOtpLog.send(resend, result.outcome(), phone, cid);
        return result;
    }

    private void verify(AuthenticationFlowContext context, String key, String phone, String raw) {
        String code = raw == null ? "" : raw.trim();
        if (!CODE.matcher(code).matches()) {
            wrongCode(context);
            return;
        }
        // The saved note is the only phone this step will check. The form is not read.
        SmsOtpCalls.Result result = client.verify(key, phone, code);
        switch (result.outcome()) {
            case VERIFIED -> {
                if (result.phone() == null || !result.phone().equals(phone)) {
                    wrongCode(context);
                    return;
                }
                signIn(context, phone);
            }
            case INVALID_CODE -> wrongCode(context);
            case EXPIRED -> context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    codePage(context, MSG_EXPIRED, null));
            case TOO_MANY_ATTEMPTS -> context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    codePage(context, MSG_TOO_MANY_CODES, null));
            case RATE_LIMITED -> context.challenge(rateLimited(context, false, result.retryAfterSeconds()));
            case CHECK_UNAVAILABLE -> context.challenge(codePage(context, MSG_CHECK_UNAVAILABLE, code));
            default -> context.challenge(codePage(context, MSG_UNAVAILABLE, null));
        }
    }

    private void wrongCode(AuthenticationFlowContext context) {
        int wrong = count(context, NOTE_WRONG_CODES) + 1;
        session(context).setAuthNote(NOTE_WRONG_CODES, Integer.toString(wrong));
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, codePage(context, MSG_WRONG_CODE, null));
    }

    /**
     * Signs in when exactly one user with this phone attribute passes the policy.
     * Every other outcome, including a lookup error, is a wrong code.
     */
    private void signIn(AuthenticationFlowContext context, String phone) {
        List<UserModel> matches;
        try {
            matches = context.getSession().users()
                    .searchForUserByUserAttributeStream(context.getRealm(), PhoneSignInPolicy.PHONE_ATTRIBUTE, phone)
                    .filter(candidate -> PhoneSignInPolicy.allows(candidate, phone))
                    .limit(2)
                    .toList();
        } catch (RuntimeException ex) {
            wrongCode(context);
            return;
        }
        if (matches.size() != 1) {
            wrongCode(context);
            return;
        }
        clearPending(context);
        context.setUser(matches.get(0));
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

    /** Whole minutes, rounded up. One second is one minute. */
    static int retryMinutes(Integer retryAfterSeconds) {
        int seconds = retryAfterSeconds == null
                ? SmsOtpCalls.DEFAULT_RETRY_AFTER_SECONDS
                : retryAfterSeconds;
        if (seconds < 1) {
            seconds = SmsOtpCalls.DEFAULT_RETRY_AFTER_SECONDS;
        }
        return (seconds + 59) / 60;
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
        LoginFormsProvider form = context.form().setAttribute(ATTR_AGREEMENT, SignInAgreement.WORDING);
        if (error != null) {
            form.setError(error);
        }
        return form.createForm(PHONE_TEMPLATE);
    }

    private static Response codePage(AuthenticationFlowContext context, String error, String keptCode) {
        LoginFormsProvider form = context.form().setAttribute(ATTR_RESEND_ALLOWED, sendAllowed(context));
        if (keptCode != null) {
            form.setAttribute(ATTR_CODE, keptCode);
        }
        if (error != null) {
            form.setError(error);
        }
        return form.createForm(CODE_TEMPLATE);
    }

    private static Response rateLimited(AuthenticationFlowContext context, boolean phoneForm, Integer retryAfterSeconds) {
        int minutes = retryMinutes(retryAfterSeconds);
        LoginFormsProvider form = context.form();
        if (phoneForm) {
            form.setAttribute(ATTR_AGREEMENT, SignInAgreement.WORDING);
        } else {
            form.setAttribute(ATTR_RESEND_ALLOWED, sendAllowed(context));
        }
        if (minutes == 1) {
            form.setError(MSG_RATE_LIMITED_ONE);
        } else {
            form.setError(MSG_RATE_LIMITED, minutes);
        }
        return form.createForm(phoneForm ? PHONE_TEMPLATE : CODE_TEMPLATE);
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
