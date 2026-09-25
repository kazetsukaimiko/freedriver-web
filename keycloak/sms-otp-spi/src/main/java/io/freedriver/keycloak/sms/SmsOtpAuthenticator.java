package io.freedriver.keycloak.sms;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phone + code sign-in as an Alternative beside the password form.
 * With an empty or placeholder secret the execution marks itself {@code attempted}.
 * Sign-in completes only on the documented 200 responses from sms.
 */
public final class SmsOtpAuthenticator implements Authenticator {

    static final String NOTE_PHONE = "freedriver.sms.phone";
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{4,10}$");
    private static final String UNAVAILABLE =
            "Phone sign-in is unavailable. Password sign-in still works.";

    private final SmsOtpClient client;

    public SmsOtpAuthenticator() {
        this(new SmsOtpClient());
    }

    SmsOtpAuthenticator(SmsOtpClient client) {
        this.client = client;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (SmsOtpConfig.secret().isEmpty()) {
            context.attempted();
            return;
        }
        context.challenge(page(context, null, null));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        Optional<String> secret = SmsOtpConfig.secret();
        if (secret.isEmpty()) {
            context.attempted();
            return;
        }
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        if (form.containsKey("tryAnotherWay")) {
            context.getAuthenticationSession().removeAuthNote(NOTE_PHONE);
            context.attempted();
            return;
        }
        String noted = context.getAuthenticationSession().getAuthNote(NOTE_PHONE);
        if (form.containsKey("resend")) {
            send(context, secret.get(), noted);
            return;
        }
        String code = form.getFirst("code");
        if (noted != null && code != null && !code.isBlank()) {
            verify(context, secret.get(), noted, code.trim());
            return;
        }
        send(context, secret.get(), form.getFirst("phone"));
    }

    private void send(AuthenticationFlowContext context, String secret, String phone) {
        context.getAuthenticationSession().removeAuthNote(NOTE_PHONE);
        String normalized = phone == null ? "" : phone.trim().replace(" ", "");
        if (!PHONE.matcher(normalized).matches()) {
            context.challenge(page(context, null, "Enter the house phone number in international form."));
            return;
        }
        SmsOtpClient.Result result = client.send(secret, normalized);
        if (result.outcome() != SmsOtpClient.Outcome.SENT) {
            context.challenge(page(context, null, UNAVAILABLE));
            return;
        }
        context.getAuthenticationSession().setAuthNote(NOTE_PHONE, normalized);
        context.challenge(page(context, normalized, null));
    }

    private void verify(AuthenticationFlowContext context, String secret, String phone, String code) {
        if (!CODE.matcher(code).matches()) {
            context.challenge(page(context, phone, UNAVAILABLE));
            return;
        }
        SmsOtpClient.Result result = client.verify(secret, phone, code);
        if (result.outcome() != SmsOtpClient.Outcome.VERIFIED || result.username() == null) {
            context.challenge(page(context, phone, UNAVAILABLE));
            return;
        }
        UserModel user = context.getSession().users().getUserByUsername(context.getRealm(), result.username());
        if (user == null || !user.isEnabled()) {
            context.challenge(page(context, phone, UNAVAILABLE));
            return;
        }
        context.setUser(user);
        context.success();
    }

    private static Response page(AuthenticationFlowContext context, String phone, String message) {
        String action = context.getActionUrl(context.generateAccessCode()).toString();
        String html = phone == null ? phoneForm(action, message) : codeForm(action, phone, message);
        return Response.ok(html, MediaType.TEXT_HTML_TYPE).build();
    }

    private static String phoneForm(String action, String message) {
        return shell(message, """
                <form method="post" action="%s">
                  <label>Phone
                    <input name="phone" autocomplete="tel" inputmode="tel" required>
                  </label>
                  <button type="submit">Send code</button>
                </form>
                %s
                """.formatted(escape(action), tryAnother(action)));
    }

    private static String codeForm(String action, String phone, String message) {
        return shell(message, """
                <p>Code sent to %s</p>
                <form method="post" action="%s">
                  <label>Code
                    <input name="code" autocomplete="one-time-code" inputmode="numeric" required>
                  </label>
                  <button type="submit">Sign in</button>
                </form>
                <form method="post" action="%s">
                  <button type="submit" name="resend" value="1">Resend code</button>
                </form>
                %s
                """.formatted(escape(mask(phone)), escape(action), escape(action), tryAnother(action)));
    }

    private static String tryAnother(String action) {
        return """
                <form method="post" action="%s">
                  <button type="submit" name="tryAnotherWay" value="on">Use password instead</button>
                </form>
                """.formatted(escape(action));
    }

    private static String shell(String message, String body) {
        String banner = message == null ? "" : "<p role=\"alert\">" + escape(message) + "</p>";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Phone sign-in</title></head>
                <body>
                  <h1>Phone sign-in</h1>
                  %s
                  %s
                </body>
                </html>
                """.formatted(banner, body);
    }

    static String mask(String phone) {
        if (phone.length() < 4) {
            return "••••";
        }
        return "••••" + phone.substring(phone.length() - 2);
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return SmsOtpConfig.secret().isPresent();
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Number hand-out lives in portal-admin on app.freedriver.io (#107).
    }

    @Override
    public void close() {}
}
