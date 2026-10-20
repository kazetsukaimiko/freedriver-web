package io.freedriver.keycloak.sms;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsOtpAuthenticatorTest {

    static final String PHONE = "+15555550100";
    static final String OK_USER = "{\"username\":\"house.user\"}";
    static final String WRONG = "{\"error\":\"invalid-code\"}";

    /** One rendered page: template, error key and attributes. */
    record Page(String template, String error, Map<String, Object> attributes) {}

    private final Map<String, String> notes = new HashMap<>();
    private final Map<Response, Page> pages = new IdentityHashMap<>();
    private final List<String> sends = new ArrayList<>();
    private final Deque<SmsOtpClient.Wire> verifyReplies = new ArrayDeque<>();
    private SmsOtpClient.Wire sendReply;

    private AuthenticationFlowContext context;
    private MultivaluedHashMap<String, String> params;
    private UserProvider users;
    private UserModel user;
    private SmsOtpAuthenticator authenticator;

    private String outcome;
    private Response shown;
    private AuthenticationFlowError failure;

    @BeforeEach
    void setUp() {
        sendReply = new SmsOtpClient.Wire(200, "{\"status\":\"sent\"}");
        SmsOtpClient client = new SmsOtpClient((uri, secret, json) -> {
            if (uri.getPath().equals("/otp/send")) {
                sends.add(SmsOtpClient.textField(json, "phone"));
                return sendReply;
            }
            return verifyReplies.isEmpty() ? new SmsOtpClient.Wire(503, "{}") : verifyReplies.pop();
        });
        authenticator = new SmsOtpAuthenticator(client, () -> Optional.of("real-secret"));

        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getAuthNote(anyString())).thenAnswer(inv -> notes.get(inv.<String>getArgument(0)));
        doAnswer(inv -> notes.put(inv.getArgument(0), inv.getArgument(1)))
                .when(authSession).setAuthNote(anyString(), anyString());
        doAnswer(inv -> notes.remove(inv.<String>getArgument(0)))
                .when(authSession).removeAuthNote(anyString());

        HttpRequest http = mock(HttpRequest.class);
        when(http.getDecodedFormParameters()).thenAnswer(inv -> params);

        users = mock(UserProvider.class);
        KeycloakSession session = mock(KeycloakSession.class);
        when(session.users()).thenReturn(users);
        RealmModel realm = mock(RealmModel.class);

        context = mock(AuthenticationFlowContext.class);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.getHttpRequest()).thenReturn(http);
        when(context.getSession()).thenReturn(session);
        when(context.getRealm()).thenReturn(realm);
        when(context.form()).thenAnswer(inv -> newForm());
        doAnswer(inv -> record("challenge", inv.getArgument(0), null)).when(context).challenge(any());
        doAnswer(inv -> record("failure", inv.getArgument(1), inv.getArgument(0)))
                .when(context).failureChallenge(any(), any());
        doAnswer(inv -> record("success", null, null)).when(context).success();
        doAnswer(inv -> record("attempted", null, null)).when(context).attempted();

        user = mock(UserModel.class);
        GroupModel group = PhoneSignInPolicyTest.group("phone-sign-in", null);
        when(user.isEnabled()).thenReturn(true);
        when(user.getFirstAttribute("phone")).thenReturn(PHONE);
        when(user.getGroupsStream()).thenAnswer(inv -> Stream.of(group));
        when(user.getRoleMappingsStream()).thenAnswer(inv -> Stream.empty());
        when(users.getUserByUsername(realm, "house.user")).thenReturn(user);
    }

    private Object record(String what, Response response, AuthenticationFlowError error) {
        outcome = what;
        shown = response;
        failure = error;
        return null;
    }

    private LoginFormsProvider newForm() {
        Map<String, Object> attributes = new HashMap<>();
        String[] error = new String[1];
        LoginFormsProvider[] self = new LoginFormsProvider[1];
        self[0] = mock(LoginFormsProvider.class, inv -> switch (inv.getMethod().getName()) {
            case "setAttribute" -> {
                attributes.put(inv.getArgument(0), inv.getArgument(1));
                yield self[0];
            }
            case "setError" -> {
                error[0] = inv.getArgument(0);
                yield self[0];
            }
            case "createForm" -> {
                Response response = mock(Response.class);
                pages.put(response, new Page(inv.getArgument(0), error[0], Map.copyOf(attributes)));
                yield response;
            }
            default -> null;
        });
        return self[0];
    }

    private Page page() {
        return pages.get(shown);
    }

    private void post(String... pairs) {
        params = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        authenticator.action(context);
    }

    private void enterPhone() {
        authenticator.authenticate(context);
        assertEquals(SmsOtpAuthenticator.PHONE_TEMPLATE, page().template());
        post("phone", "+1 555 555 0100");
        assertEquals(SmsOtpAuthenticator.CODE_TEMPLATE, page().template());
    }

    private void assertPage(String template, String error) {
        assertEquals(template, page().template());
        assertEquals(error, page().error());
    }

    @Test
    void emptyOrPlaceholderSecretSkipsTheStep() {
        for (String raw : new String[] {null, "", SmsOtpConfig.PLACEHOLDER}) {
            SmsOtpAuthenticator off = new SmsOtpAuthenticator(new SmsOtpClient(), () -> SmsOtpConfig.normalize(raw));
            outcome = null;
            off.authenticate(context);
            assertEquals("attempted", outcome);
            outcome = null;
            params = new MultivaluedHashMap<>();
            params.add("phone", PHONE);
            off.action(context);
            assertEquals("attempted", outcome);
            assertEquals(false, off.configuredFor(null, null, null));
        }
        assertTrue(sends.isEmpty());
        assertTrue(pages.isEmpty());
    }

    @Test
    void correctCodeForAllowedUserSignsIn() {
        enterPhone();
        assertEquals(List.of(PHONE), sends);
        verifyReplies.add(new SmsOtpClient.Wire(200, OK_USER));
        post("code", "123456");
        assertEquals("success", outcome);
        verify(context).setUser(user);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void codePageCarriesOnlyTheResendFlag() {
        enterPhone();
        assertEquals(Map.of(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED, true), page().attributes());
        assertNull(page().error());
    }

    @Test
    void verifiedCodeForUnknownUsernameIsDenied() {
        enterPhone();
        verifyReplies.add(new SmsOtpClient.Wire(200, "{\"username\":\"someone.else\"}"));
        post("code", "123456");
        assertDenied();
    }

    @Test
    void verifiedCodeForUserOutsideThePolicyIsDenied() {
        when(user.getGroupsStream()).thenAnswer(inv -> Stream.empty());
        enterPhone();
        verifyReplies.add(new SmsOtpClient.Wire(200, OK_USER));
        post("code", "123456");
        assertDenied();
    }

    @Test
    void verifiedCodeWithPhoneMismatchIsDenied() {
        when(user.getFirstAttribute("phone")).thenReturn("+15555550199");
        enterPhone();
        verifyReplies.add(new SmsOtpClient.Wire(200, OK_USER));
        post("code", "123456");
        assertDenied();
    }

    @Test
    void userLookupFailureIsDenied() {
        when(users.getUserByUsername(any(), anyString())).thenThrow(new IllegalStateException("store down"));
        enterPhone();
        verifyReplies.add(new SmsOtpClient.Wire(200, OK_USER));
        post("code", "123456");
        assertDenied();
    }

    private void assertDenied() {
        assertEquals("failure", outcome);
        assertSame(AuthenticationFlowError.ACCESS_DENIED, failure);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_DENIED);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        verify(context, never()).setUser(any());
        verify(context, never()).success();
    }

    @Test
    void wrongCodeStaysOnTheCodeForm() {
        enterPhone();
        verifyReplies.add(new SmsOtpClient.Wire(400, WRONG));
        post("code", "000000");
        assertEquals("failure", outcome);
        assertSame(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_WRONG_CODE);
        assertEquals("1", notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));
    }

    @Test
    void fifthWrongCodeReturnsToThePhoneFormAndClearsThePendingCode() {
        enterPhone();
        for (int i = 1; i < SmsOtpAuthenticator.MAX_WRONG_CODES; i++) {
            verifyReplies.add(new SmsOtpClient.Wire(400, WRONG));
            post("code", "000000");
            assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_WRONG_CODE);
        }
        // A malformed code counts as a wrong code and skips the sms call.
        post("code", "12ab");
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_TOO_MANY_TRIES);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));

        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, null);
        assertEquals(2, sends.size());
    }

    @Test
    void resendWorksThreeTimesThenShowsTheCodeLimit() {
        enterPhone();
        for (int i = 1; i <= SmsOtpAuthenticator.MAX_RESENDS; i++) {
            assertEquals(true, page().attributes().get(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED));
            post("resend", "1");
            assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, null);
        }
        assertEquals(1 + SmsOtpAuthenticator.MAX_RESENDS, sends.size());
        assertEquals(false, page().attributes().get(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED));

        post("resend", "1");
        assertEquals(1 + SmsOtpAuthenticator.MAX_RESENDS, sends.size());
        assertEquals(false, page().attributes().get(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED));
    }

    @Test
    void fifthWrongCodeWithTextsUsedUpShowsTheCodeLimitOnThePhoneForm() {
        enterPhone();
        for (int i = 1; i <= SmsOtpAuthenticator.MAX_RESENDS; i++) {
            post("resend", "1");
        }
        for (int i = 1; i <= SmsOtpAuthenticator.MAX_WRONG_CODES; i++) {
            verifyReplies.add(new SmsOtpClient.Wire(400, WRONG));
            post("code", "000000");
        }
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));

        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        assertEquals(1 + SmsOtpAuthenticator.MAX_RESENDS, sends.size());
    }

    @Test
    void phoneFormAfterTextsUsedUpShowsTheCodeLimitAndSkipsTheSend() {
        notes.put(SmsOtpAuthenticator.NOTE_SENDS, Integer.toString(1 + SmsOtpAuthenticator.MAX_RESENDS));
        authenticator.authenticate(context);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        post("phone", "not-a-number");
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        assertTrue(sends.isEmpty());
    }

    @Test
    void malformedSendCountClosesTheCap() {
        notes.put(SmsOtpAuthenticator.NOTE_SENDS, "junk");
        authenticator.authenticate(context);
        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        assertTrue(sends.isEmpty());
    }

    @Test
    void invalidPhoneStaysOnThePhoneForm() {
        authenticator.authenticate(context);
        post("phone", "555-0100");
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_PHONE_INVALID);
        assertTrue(sends.isEmpty());
    }

    @Test
    void smsUnavailableStaysOnThePhoneForm() {
        sendReply = new SmsOtpClient.Wire(503, "{\"error\":\"stub\"}");
        authenticator.authenticate(context);
        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_UNAVAILABLE);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void verifyUnavailableKeepsTheCodeFormWithoutCountingAWrongCode() {
        enterPhone();
        verifyReplies.add(new SmsOtpClient.Wire(503, "{\"error\":\"stub\"}"));
        post("code", "123456");
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_UNAVAILABLE);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));
    }
}
