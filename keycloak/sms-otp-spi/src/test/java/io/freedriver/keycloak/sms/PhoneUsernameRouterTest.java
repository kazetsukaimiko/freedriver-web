package io.freedriver.keycloak.sms;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhoneUsernameRouterTest {

    private static final String SECRET = "super-secret-value";
    private static final String CANONICAL = "+11234567890";

    /** Every accepted shape of 1234567890, including the canonical form. */
    private static final String[] SHAPES = {
            "123-456-7890",
            "1234567890",
            "(123) 456-7890",
            "123.456.7890",
            "123 456 7890",
            "1-123-456-7890",
            "+1 123 456 7890",
            "+11234567890"
    };

    private final Map<String, String> notes = new HashMap<>();
    private final List<String> written = new ArrayList<>();
    private final Map<Response, String> pages = new IdentityHashMap<>();
    private final List<LogRecord> records = new ArrayList<>();

    private AuthenticationFlowContext context;
    private MultivaluedHashMap<String, String> params;
    private PhoneUsernameRouter router;
    private Response shown;
    private String outcome;
    private Handler handler;

    @BeforeEach
    void setUp() {
        Logger log = Logger.getLogger(PhoneUsernameRouter.class.getName());
        log.setLevel(Level.INFO);
        log.setUseParentHandlers(false);
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        log.addHandler(handler);

        router = new PhoneUsernameRouter(() -> Optional.of(SECRET));
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getAuthNote(anyString())).thenAnswer(inv -> notes.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            written.add(inv.getArgument(0));
            notes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(authSession).setAuthNote(anyString(), anyString());
        doAnswer(inv -> notes.remove(inv.<String>getArgument(0))).when(authSession).removeAuthNote(anyString());

        HttpRequest http = mock(HttpRequest.class);
        when(http.getDecodedFormParameters()).thenAnswer(inv -> params);

        context = mock(AuthenticationFlowContext.class);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.getHttpRequest()).thenReturn(http);
        when(context.form()).thenAnswer(inv -> newForm());
        when(context.getUser()).thenThrow(new AssertionError("no user"));
        when(context.getSession()).thenThrow(new AssertionError("no session"));
        doThrow(new AssertionError("setUser")).when(context).setUser(any());
        doAnswer(inv -> {
            outcome = "challenge";
            shown = inv.getArgument(0);
            return null;
        }).when(context).challenge(any());
        doAnswer(inv -> {
            outcome = "success";
            return null;
        }).when(context).success();
        doThrow(new AssertionError("credential success")).when(context).success(anyString());
    }

    @AfterEach
    void stopLogs() {
        Logger log = Logger.getLogger(PhoneUsernameRouter.class.getName());
        log.removeHandler(handler);
    }

    private LoginFormsProvider newForm() {
        LoginFormsProvider[] self = new LoginFormsProvider[1];
        self[0] = mock(LoginFormsProvider.class, inv -> switch (inv.getMethod().getName()) {
            case "setAttribute", "setError" -> self[0];
            case "createForm" -> {
                Response response = mock(Response.class);
                pages.put(response, inv.getArgument(0));
                yield response;
            }
            default -> null;
        });
        return self[0];
    }

    private void post(PhoneUsernameRouter step, String value) {
        records.clear();
        outcome = null;
        params = new MultivaluedHashMap<>();
        if (value != null) {
            params.add(PhoneUsernameRouter.FIELD, value);
        }
        step.action(context);
    }

    private String onlyLog() {
        assertEquals(1, records.size());
        LogRecord record = records.get(0);
        assertNull(record.getParameters());
        assertNull(record.getThrown());
        return record.getMessage();
    }

    private void assertNoUserAndNoSession() {
        verify(context, never()).setUser(any());
        verify(context, never()).getUser();
        verify(context, never()).getSession();
        verify(context, never()).success(anyString());
        assertFalse(written.contains(SmsOtpAuthenticator.NOTE_PHONE));
    }

    private void assertPhone(PhoneUsernameRouter step, String raw, String canonical) {
        notes.clear();
        written.clear();
        notes.put(SmsOtpAuthenticator.NOTE_PHONE, "+19998887777");
        post(step, raw);
        assertEquals("success", outcome);
        assertEquals(PhoneUsernameRouter.ROUTE_PHONE, notes.get(PhoneUsernameRouter.NOTE_ROUTE));
        assertEquals(canonical, notes.get(PhoneUsernameRouter.NOTE_NUMBER));
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        String message = onlyLog();
        assertEquals(PhoneUsernameRouter.phoneLog(canonical), message);
        assertEquals("sms route phone " + canonical.substring(canonical.length() - 4), message);
        assertFalse(message.contains(raw));
        assertFalse(message.contains(canonical));
        assertFalse(message.contains(SECRET));
        assertTrue(message.matches("sms route phone [0-9]{4}"));
        assertNoUserAndNoSession();
    }

    private void assertUsername(PhoneUsernameRouter step, String raw) {
        notes.clear();
        written.clear();
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, CANONICAL);
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        post(step, raw);
        assertEquals("success", outcome);
        assertEquals(PhoneUsernameRouter.ROUTE_USERNAME, notes.get(PhoneUsernameRouter.NOTE_ROUTE));
        assertNull(notes.get(PhoneUsernameRouter.NOTE_NUMBER));
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        String message = onlyLog();
        assertEquals(PhoneUsernameRouter.USERNAME_LOG, message);
        if (raw != null && !raw.isEmpty()) {
            assertFalse(message.contains(raw));
        }
        assertFalse(message.contains(CANONICAL));
        assertFalse(message.contains(SECRET));
        assertFalse(message.chars().anyMatch(Character::isDigit));
        assertNoUserAndNoSession();
    }

    @Test
    void showsThePhoneOrUsernameForm() {
        records.clear();
        router.authenticate(context);
        assertEquals("challenge", outcome);
        assertEquals(PhoneUsernameRouter.TEMPLATE, pages.get(shown));
        assertTrue(records.isEmpty());
        assertNoUserAndNoSession();
    }

    @Test
    void everyListedShapeAndTheCanonicalFormRouteToThePhone() {
        for (String raw : SHAPES) {
            assertPhone(router, raw, CANONICAL);
        }
    }

    @Test
    void shapesMatchAnyTenDigitNumber() {
        assertPhone(router, "555-010-0199", "+15550100199");
        assertPhone(router, "5550100199", "+15550100199");
        assertPhone(router, "(555) 010-0199", "+15550100199");
        assertPhone(router, "555.010.0199", "+15550100199");
        assertPhone(router, "555 010 0199", "+15550100199");
        assertPhone(router, "1-555-010-0199", "+15550100199");
        assertPhone(router, "+1 555 010 0199", "+15550100199");
        assertPhone(router, "+15550100199", "+15550100199");
        assertPhone(router, "0000000000", "+10000000000");
    }

    @Test
    void extraCharactersStayAUsername() {
        for (String raw : new String[] {
                "123-456-7890x",
                "x123-456-7890",
                "12345678901",
                "11234567890",
                "+112345678901",
                "123-456-7890 ",
                " 123-456-7890",
                "see 123-456-7890",
                "(123) 456-7890 ",
                "+1 123 456 7890x",
                "+1-123-456-7890",
                "(123)456-7890",
                "123 456-7890",
                "1 123 456 7890"
        }) {
            assertUsername(router, raw);
        }
    }

    @Test
    void nonPhoneUsernameIsNotLookedUpOrRewritten() {
        assertUsername(router, "house.user");
        assertUsername(router, "ada");
        assertUsername(router, "");
        post(router, null);
        assertEquals(PhoneUsernameRouter.ROUTE_USERNAME, notes.get(PhoneUsernameRouter.NOTE_ROUTE));
        assertNull(notes.get(PhoneUsernameRouter.NOTE_NUMBER));
        assertNoUserAndNoSession();
    }

    @Test
    void switchedOffRoutesAPhoneToUsernameAndSendsNothing() {
        for (String raw : new String[] {null, "", "   ", SmsOtpConfig.PLACEHOLDER, "  " + SmsOtpConfig.PLACEHOLDER + "\n"}) {
            PhoneUsernameRouter off = new PhoneUsernameRouter(() -> SmsOtpConfig.normalize(raw));
            for (String entry : new String[] {"123-456-7890", "+11234567890", "house.user"}) {
                assertUsername(off, entry);
            }
        }
    }

    @Test
    void factoryIsRequiredOnlyAndDoesNotReplaceSmsOtp() {
        PhoneUsernameRouterFactory factory = new PhoneUsernameRouterFactory();
        assertEquals("freedriver-phone-username-router", factory.getId());
        assertEquals("freedriver-sms-otp", new SmsOtpAuthenticatorFactory().getId());
        List<AuthenticationExecutionModel.Requirement> choices = Arrays.asList(factory.getRequirementChoices());
        assertEquals(List.of(
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.DISABLED), choices);
        assertFalse(choices.contains(AuthenticationExecutionModel.Requirement.ALTERNATIVE));
        assertFalse(factory.isConfigurable());
        assertFalse(factory.isUserSetupAllowed());
        assertTrue(factory.create(null) instanceof PhoneUsernameRouter);
    }

    @Test
    void pageCopyAndAuthenticityToken() throws IOException {
        String form = repoText(
                "keycloak/themes/freedriver/login/freedriver-phone-username.ftl",
                "../themes/freedriver/login/freedriver-phone-username.ftl",
                "themes/freedriver/login/freedriver-phone-username.ftl");
        assertTrue(form.contains("action=\"${url.loginAction}\""));
        assertTrue(form.contains("name=\"username\""));
        assertTrue(form.contains("freedriverPhoneUsernameLabel"));
        assertTrue(form.contains("freedriverPhoneUsernameContinue"));

        String messages = repoText(
                "keycloak/themes/freedriver/login/messages/messages_en.properties",
                "../themes/freedriver/login/messages/messages_en.properties",
                "themes/freedriver/login/messages/messages_en.properties");
        assertTrue(messages.contains("freedriverPhoneUsernameLabel=Phone number or username"));
        assertTrue(messages.contains("freedriverPhoneUsernameContinue=Continue"));
        assertTrue(messages.contains(
                "freedriverSmsCodeHint=If this number can sign in, we''ve texted you a code."));
        assertTrue(messages.contains(
                "freedriverSmsWrongCode=That code didn''t work. Request a new one."));
        assertTrue(messages.contains(
                "freedriverSmsDenied=That code didn''t work. Request a new one."));
        assertTrue(messages.contains(
                "freedriverSmsCodeLimit=Code limit reached. Try again in 15 minutes."));
        assertTrue(messages.contains(
                "freedriverSmsTooManyTries=Too many tries. Enter your phone number to get a new code."));
        assertFalse(messages.contains("unavailable for this account"));
        assertFalse(messages.contains("allowlist"));

        String services = repoText(
                "keycloak/sms-otp-spi/src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory",
                "src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory");
        assertTrue(services.contains("io.freedriver.keycloak.sms.SmsOtpAuthenticatorFactory"));
        assertTrue(services.contains("io.freedriver.keycloak.sms.PhoneUsernameRouterFactory"));
        assertTrue(services.contains("io.freedriver.keycloak.sms.SmsRouteConditionFactory"));
    }

    private static String repoText(String... candidates) throws IOException {
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new AssertionError("missing " + String.join(" or ", candidates));
    }
}
