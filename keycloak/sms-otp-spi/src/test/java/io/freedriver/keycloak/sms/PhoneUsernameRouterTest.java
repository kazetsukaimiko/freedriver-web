package io.freedriver.keycloak.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** Surefire's working directory is this module. */
    private static final Path ROUTER_SHAPES =
            Path.of("../../sms-model/src/test/resources/us-phone-shapes.json");

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Shapes(List<Example> dashboard, List<Example> router, List<String> nonMatches) {
    }

    private record Example(String raw, String stored) {
    }

    private final Map<String, String> notes = new HashMap<>();
    private final Map<String, Object> formAttributes = new HashMap<>();
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
        RealmModel realm = mock(RealmModel.class);
        when(realm.getAccessCodeLifespanLogin()).thenReturn(7200);
        when(context.getRealm()).thenReturn(realm);
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
            case "setAttribute" -> {
                formAttributes.put(inv.getArgument(0), inv.getArgument(1));
                yield self[0];
            }
            case "setError" -> self[0];
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
        assertEquals(SignInAgreement.WORDING, formAttributes.get(SmsOtpAuthenticator.ATTR_AGREEMENT));
        assertEquals(7200, formAttributes.get(SignInLifetime.ATTR));
        assertTrue(records.isEmpty());
        assertNoUserAndNoSession();
    }

    static Stream<Example> routerShapes() throws IOException {
        assertTrue(Files.isRegularFile(ROUTER_SHAPES), "missing " + ROUTER_SHAPES);
        Shapes shapes;
        try (InputStream in = Files.newInputStream(ROUTER_SHAPES)) {
            shapes = new ObjectMapper().readValue(in, Shapes.class);
        }
        assertNotNull(shapes.router(), "router group is missing in " + ROUTER_SHAPES);
        assertFalse(shapes.router().isEmpty(), "router group is empty in " + ROUTER_SHAPES);
        return shapes.router().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routerShapes")
    void everyListedShapeAndTheCanonicalFormRouteToThePhone(Example example) {
        assertPhone(router, example.raw(), example.stored());
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
    void aNonUsE164StaysAUsername() {
        assertUsername(router, "+447700900123");
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
        String form = moduleText("theme/freedriver/login/freedriver-phone-username.ftl");
        assertTrue(form.contains("action=\"${url.loginAction}\""));
        assertTrue(form.contains("name=\"username\""));
        assertTrue(form.contains("freedriverPhoneUsernameLabel"));
        assertTrue(form.contains("freedriverPhoneUsernameContinue"));
        assertTrue(form.contains("id=\"freedriver-sms-agreement\""));
        assertTrue(form.contains("${freedriverSmsAgreement}"));
        assertTrue(form.contains("phone-fragment.js"));
        assertTrue(form.contains("${url.resourcesPath}/js/us-phone.js"));
        assertTrue(form.indexOf("us-phone.js") < form.indexOf("phone-fragment.js"));
        assertFalse(form.contains("By tapping Send code"));
        assertFalse(form.contains("name=\"send\""));
        assertFalse(form.contains("freedriverSmsSend"));
        assertTrue(form.indexOf("name=\"username\"") < form.indexOf("id=\"freedriver-sms-agreement\""));
        assertTrue(form.indexOf("id=\"freedriver-sms-agreement\"") < form.indexOf("freedriverPhoneUsernameContinue"));

        String script = moduleText("theme/freedriver/login/resources/js/phone-fragment.js");
        assertTrue(script.contains("location.hash"));
        assertTrue(script.contains("sessionStorage"));
        assertTrue(script.contains("freedriver.login.phone"));
        assertTrue(script.contains("freedriver-phone-username-form"));
        assertFalse(script.contains("location.search"));
        assertFalse(script.contains("URLSearchParams"));

        List<String> messageLines = Files.readAllLines(
                moduleResource("theme/freedriver/login/messages/messages_en.properties"));
        String messages = String.join("\n", messageLines);
        for (String line : List.of(
                "freedriverPhoneUsernameLabel=Phone number or username",
                "freedriverPhoneUsernameContinue=Continue",
                "freedriverSmsCodeHint=Enter the 6-digit code we texted to your phone.",
                "freedriverSmsWrongCode=That code didn''t work. Try again or send a new code.",
                "freedriverSmsDenied=That code didn''t work. Request a new one.",
                "freedriverSmsCodeLimit=Code limit reached. Try again in 15 minutes.",
                "freedriverSmsCodeExpiry=Your code works for 5 minutes.",
                "freedriverSmsExpired=That code expired. Send a new code.",
                "freedriverSmsTooManyCodes=Too many wrong codes. Send a new code.")) {
            assertTrue(messageLines.contains(line), line);
        }
        assertFalse(messages.contains("unavailable for this account"));
        assertFalse(messages.contains("allowlist"));

        String services = moduleText(
                "META-INF/services/org.keycloak.authentication.AuthenticatorFactory");
        assertTrue(services.contains("io.freedriver.keycloak.sms.SmsOtpAuthenticatorFactory"));
        assertTrue(services.contains("io.freedriver.keycloak.sms.PhoneUsernameRouterFactory"));
        assertTrue(services.contains("io.freedriver.keycloak.sms.SmsRouteConditionFactory"));
    }

    /** Surefire's working directory is this module. */
    private static Path moduleResource(String relative) {
        return Path.of("src/main/resources").resolve(relative);
    }

    private static String moduleText(String relative) throws IOException {
        Path path = moduleResource(relative);
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("missing " + path);
        }
        return Files.readString(path);
    }
}
