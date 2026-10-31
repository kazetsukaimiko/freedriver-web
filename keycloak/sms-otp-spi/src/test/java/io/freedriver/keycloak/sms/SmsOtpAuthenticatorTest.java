package io.freedriver.keycloak.sms;

import io.freedriver.sms.api.SmsApi;
import io.freedriver.sms.client.SmsClientException;
import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SendRequest;
import io.freedriver.sms.model.SendType;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsOtpAuthenticatorTest {

    static final String PHONE = "+15555550100";

    /** One rendered page: template, error key, error parameters and attributes. */
    record Page(String template, String error, List<Object> errorArgs, Map<String, Object> attributes) {}

    /** Scripted sms-client failure, or a 200 whose verified phone is {@code phone}. */
    record Scripted(int status, String error, Long retryAfterSeconds, String phone, String reason) {
        Scripted(int status, String error, Long retryAfterSeconds, String phone) {
            this(status, error, retryAfterSeconds, phone, null);
        }
    }

    private final Map<String, String> notes = new HashMap<>();
    private final Map<Response, Page> pages = new IdentityHashMap<>();
    private final List<String> sends = new ArrayList<>();
    private final List<OtpSendRequest> sendRequests = new ArrayList<>();
    private final List<VerifyRequest> verifyRequests = new ArrayList<>();
    private final Deque<Scripted> verifyReplies = new ArrayDeque<>();
    private int sendStatus = 200;
    private String sendError;
    private Long sendRetry;
    private String sendResponsePhone;

    private AuthenticationFlowContext context;
    private MultivaluedHashMap<String, String> params;
    private UserProvider users;
    private UserModel user;
    private GroupModel group;
    private RealmModel realm;
    private SmsOtpAuthenticator authenticator;

    private String outcome;
    private Response shown;
    private AuthenticationFlowError failure;

    @BeforeEach
    void setUp() {
        sendStatus = 200;
        sendError = null;
        sendRetry = null;
        sendResponsePhone = null;
        SmsOtpCalls client = new SmsOtpCalls(secret -> new SmsApi() {
            @Override
            public SentResponse send(SendRequest request) {
                OtpSendRequest otp = (OtpSendRequest) request;
                sendRequests.add(otp);
                sends.add(otp.phone());
                if (sendStatus != 200) {
                    throw new SmsClientException(sendStatus, sendError, sendRetry);
                }
                String phone = sendResponsePhone != null ? sendResponsePhone : otp.phone();
                return SentResponse.otp(phone);
            }

            @Override
            public VerifiedResponse verify(VerifyRequest request) {
                verifyRequests.add(request);
                Scripted reply = verifyReplies.isEmpty()
                        ? new Scripted(503, "unavailable", null, null)
                        : verifyReplies.pop();
                if (reply.status != 200) {
                    throw new SmsClientException(reply.status, reply.error, reply.retryAfterSeconds, reply.reason);
                }
                return VerifiedResponse.otp(reply.phone);
            }
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
        realm = mock(RealmModel.class);

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
        group = PhoneSignInPolicyTest.group("phone-sign-in", null);
        when(user.isEnabled()).thenReturn(true);
        when(user.getFirstAttribute("phone")).thenReturn(PHONE);
        when(user.getGroupsStream()).thenAnswer(inv -> Stream.of(group));
        when(user.getRoleMappingsStream()).thenAnswer(inv -> Stream.empty());
        when(users.searchForUserByUserAttributeStream(realm, "phone", PHONE))
                .thenAnswer(inv -> Stream.of(user));
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
        List<Object> errorArgs = new ArrayList<>();
        LoginFormsProvider[] self = new LoginFormsProvider[1];
        self[0] = mock(LoginFormsProvider.class, inv -> switch (inv.getMethod().getName()) {
            case "setAttribute" -> {
                attributes.put(inv.getArgument(0), inv.getArgument(1));
                yield self[0];
            }
            case "setError" -> {
                error[0] = inv.getArgument(0);
                errorArgs.clear();
                for (int i = 1; i < inv.getArguments().length; i++) {
                    errorArgs.add(inv.getArgument(i));
                }
                yield self[0];
            }
            case "createForm" -> {
                Response response = mock(Response.class);
                pages.put(response, new Page(inv.getArgument(0), error[0], List.copyOf(errorArgs), Map.copyOf(attributes)));
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
        assertEquals(SignInAgreement.WORDING, page().attributes().get(SmsOtpAuthenticator.ATTR_AGREEMENT));
        post("phone", "+1 555 555 0100");
        assertEquals(SmsOtpAuthenticator.CODE_TEMPLATE, page().template());
    }

    private void assertPage(String template, String error) {
        assertEquals(template, page().template());
        assertEquals(error, page().error());
    }

    private void assertWrongCode() {
        assertEquals("failure", outcome);
        assertSame(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_WRONG_CODE);
        assertEquals(PHONE, notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        verify(context, never()).setUser(any());
        verify(context, never()).success();
    }

    @Test
    void emptyOrPlaceholderSecretSkipsTheStep() {
        for (String raw : new String[] {null, "", SmsOtpConfig.PLACEHOLDER}) {
            SmsOtpAuthenticator off = new SmsOtpAuthenticator(new SmsOtpCalls(secret -> {
                throw new AssertionError("sms client called");
            }), () -> SmsOtpConfig.normalize(raw));
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
    void phonePageShowsTheSharedAgreementWording() {
        authenticator.authenticate(context);
        assertEquals(SignInAgreement.WORDING, page().attributes().get(SmsOtpAuthenticator.ATTR_AGREEMENT));
    }

    @Test
    void phoneRouteSendsTheStoredNumberAndShowsTheCodePage() {
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, "+19876543210");
        sendResponsePhone = "+19876543210";
        authenticator.authenticate(context);
        assertEquals(List.of("+19876543210"), sends);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, null);
        assertEquals("+19876543210", notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        assertEquals("1", notes.get(SmsOtpAuthenticator.NOTE_SENDS));

        authenticator.authenticate(context);
        assertEquals(1, sends.size());
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, null);
    }

    @Test
    void phoneRouteStopsAtTheExistingSendCap() {
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, "+11234567890");
        notes.put(SmsOtpAuthenticator.NOTE_SENDS, Integer.toString(1 + SmsOtpAuthenticator.MAX_RESENDS));
        authenticator.authenticate(context);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_CODE_LIMIT);
        assertTrue(sends.isEmpty());
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void usernameRouteDoesNotSend() {
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_USERNAME);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, "+11234567890");
        authenticator.authenticate(context);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, null);
        assertTrue(sends.isEmpty());
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void emptySecretAttemptsBeforeARoutedSend() {
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, "+11234567890");
        List<String> leaked = new ArrayList<>();
        SmsOtpCalls client = new SmsOtpCalls(secret -> {
            leaked.add(secret);
            throw new AssertionError("sms client called");
        });
        for (String raw : new String[] {null, "", "   ", SmsOtpConfig.PLACEHOLDER}) {
            leaked.clear();
            SmsOtpAuthenticator off = new SmsOtpAuthenticator(client, () -> SmsOtpConfig.normalize(raw));
            outcome = null;
            off.authenticate(context);
            assertEquals("attempted", outcome);
            params = new MultivaluedHashMap<>();
            params.add("phone", "+11234567890");
            off.action(context);
            assertEquals("attempted", outcome);
            assertTrue(leaked.isEmpty());
        }
        assertTrue(sends.isEmpty());
    }

    @Test
    void correctCodeForAllowedUserSignsIn() {
        enterPhone();
        assertEquals(List.of(PHONE), sends);
        assertEquals(SendType.OTP, sendRequests.getFirst().type());
        assertEquals(SignInAgreement.WORDING, sendRequests.getFirst().agreement().wording());
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertEquals("success", outcome);
        assertEquals(PHONE, verifyRequests.getFirst().phone());
        assertEquals("123456", verifyRequests.getFirst().code());
        verify(context).setUser(user);
        verify(users, never()).getUserByUsername(any(), any());
        verify(users, never()).addUser(any(), any());
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void openingTheLinkAgainSignsInTheSameUser() {
        sendResponsePhone = PHONE;
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, PHONE);
        authenticator.authenticate(context);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, null);
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertEquals("success", outcome);

        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, PHONE);
        authenticator.authenticate(context);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, null);
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertEquals("success", outcome);
        verify(context, times(2)).setUser(user);
        verify(users, never()).addUser(any(), any());
    }

    @Test
    void verifyUsesTheSavedPhoneNotTheForm() {
        enterPhone();
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456", "phone", "+19999999999");
        assertEquals(PHONE, verifyRequests.getFirst().phone());
        assertEquals("success", outcome);
        verify(context).setUser(user);
    }

    @Test
    void codePageCarriesOnlyTheResendFlag() {
        enterPhone();
        assertEquals(Map.of(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED, true), page().attributes());
        assertNull(page().error());
    }

    @Test
    void verifiedCodeWithNoMatchingUserIsAWrongCode() {
        when(users.searchForUserByUserAttributeStream(any(), anyString(), anyString()))
                .thenAnswer(inv -> Stream.empty());
        enterPhone();
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertWrongCode();
    }

    @Test
    void twoMatchingUsersAreAWrongCode() {
        UserModel other = mock(UserModel.class);
        when(other.isEnabled()).thenReturn(true);
        when(other.getFirstAttribute("phone")).thenReturn(PHONE);
        when(other.getGroupsStream()).thenAnswer(inv -> Stream.of(group));
        when(other.getRoleMappingsStream()).thenAnswer(inv -> Stream.empty());
        when(users.searchForUserByUserAttributeStream(realm, "phone", PHONE))
                .thenAnswer(inv -> Stream.of(user, other));
        enterPhone();
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertWrongCode();
    }

    @Test
    void verifiedCodeForUserOutsideThePolicyIsAWrongCode() {
        when(user.getGroupsStream()).thenAnswer(inv -> Stream.empty());
        enterPhone();
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertWrongCode();
    }

    @Test
    void verifiedPhoneThatDiffersFromTheNoteIsAWrongCode() {
        enterPhone();
        verifyReplies.add(new Scripted(200, null, null, "+15555550199"));
        post("code", "123456", "phone", "+15555550199");
        assertWrongCode();
        verify(users, never()).searchForUserByUserAttributeStream(any(), anyString(), anyString());
    }

    @Test
    void userLookupFailureIsAWrongCode() {
        when(users.searchForUserByUserAttributeStream(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("store down"));
        enterPhone();
        verifyReplies.add(new Scripted(200, null, null, PHONE));
        post("code", "123456");
        assertWrongCode();
    }

    @Test
    void wrongCodeStaysOnTheCodeForm() {
        enterPhone();
        verifyReplies.add(new Scripted(400, "invalid-code", null, null));
        post("code", "000000");
        assertEquals("failure", outcome);
        assertSame(AuthenticationFlowError.INVALID_CREDENTIALS, failure);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_WRONG_CODE);
        assertEquals("1", notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));
    }

    @Test
    void expiredCodeClearsTheFieldAndStaysOnTheCodeForm() {
        enterPhone();
        verifyReplies.add(new Scripted(400, "invalid-code", null, null, "expired"));
        post("code", "123456");
        assertEquals("failure", outcome);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_EXPIRED);
        assertNull(page().attributes().get(SmsOtpAuthenticator.ATTR_CODE));
        assertEquals(PHONE, notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void tooManyWrongCodesClearsTheFieldAndStaysOnTheCodeForm() {
        enterPhone();
        verifyReplies.add(new Scripted(429, "rate-limited", 900L, null, "too_many_attempts"));
        post("code", "123456");
        assertEquals("failure", outcome);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_TOO_MANY_CODES);
        assertNull(page().attributes().get(SmsOtpAuthenticator.ATTR_CODE));
        assertEquals(PHONE, notes.get(SmsOtpAuthenticator.NOTE_PHONE));
        assertTrue(page().errorArgs().isEmpty());
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
    void tooManyWrongCodesWithTextsUsedUpStaysOnTheCodeForm() {
        enterPhone();
        for (int i = 1; i <= SmsOtpAuthenticator.MAX_RESENDS; i++) {
            post("resend", "1");
        }
        verifyReplies.add(new Scripted(429, "rate-limited", 900L, null, "too_many_attempts"));
        post("code", "000000");
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_TOO_MANY_CODES);
        assertEquals(false, page().attributes().get(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED));
        assertEquals(PHONE, notes.get(SmsOtpAuthenticator.NOTE_PHONE));
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
        sendStatus = 503;
        sendError = "stub";
        authenticator.authenticate(context);
        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_UNAVAILABLE);
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void verifyUnavailableKeepsTheTypedCodeAndLeavesTheFormUp() {
        enterPhone();
        verifyReplies.add(new Scripted(503, "unavailable", null, null));
        post("code", "123456");
        assertEquals("challenge", outcome);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_CHECK_UNAVAILABLE);
        assertEquals("123456", page().attributes().get(SmsOtpAuthenticator.ATTR_CODE));
        assertEquals(true, page().attributes().get(SmsOtpAuthenticator.ATTR_RESEND_ALLOWED));
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));
        assertEquals(PHONE, notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void otherVerifyFailureDoesNotKeepTheCodeOrCountItWrong() {
        enterPhone();
        verifyReplies.add(new Scripted(503, "stub", null, null));
        post("code", "123456");
        assertEquals("challenge", outcome);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_UNAVAILABLE);
        assertNull(page().attributes().get(SmsOtpAuthenticator.ATTR_CODE));
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));
    }

    @Test
    void sendRateLimitRoundsRetryAfterUpToMinutes() {
        sendStatus = 429;
        sendError = "rate-limited";
        sendRetry = 61L;
        authenticator.authenticate(context);
        post("phone", PHONE);
        assertEquals("challenge", outcome);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_RATE_LIMITED);
        assertEquals(List.of(2), page().errorArgs());
        assertEquals(SignInAgreement.WORDING, page().attributes().get(SmsOtpAuthenticator.ATTR_AGREEMENT));
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void sendRateLimitOfOneMinuteUsesTheSingularLine() {
        sendStatus = 429;
        sendError = "rate-limited";
        sendRetry = 60L;
        authenticator.authenticate(context);
        post("phone", "+1 (555) 555-0100");
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_RATE_LIMITED_ONE);
        assertTrue(page().errorArgs().isEmpty());
    }

    @Test
    void unusableRetryAfterFallsBackToFifteenMinutes() {
        sendStatus = 429;
        sendError = "rate-limited";
        sendRetry = null;
        authenticator.authenticate(context);
        post("phone", PHONE);
        assertPage(SmsOtpAuthenticator.PHONE_TEMPLATE, SmsOtpAuthenticator.MSG_RATE_LIMITED);
        assertEquals(List.of(15), page().errorArgs());
    }

    @Test
    void verifyRateLimitStaysOnTheCodeForm() {
        enterPhone();
        verifyReplies.add(new Scripted(429, "rate-limited", 120L, null));
        post("code", "123456");
        assertEquals("challenge", outcome);
        assertPage(SmsOtpAuthenticator.CODE_TEMPLATE, SmsOtpAuthenticator.MSG_RATE_LIMITED);
        assertEquals(List.of(2), page().errorArgs());
        assertNull(notes.get(SmsOtpAuthenticator.NOTE_WRONG_CODES));
        assertEquals(PHONE, notes.get(SmsOtpAuthenticator.NOTE_PHONE));
    }

    @Test
    void sendLogsOutcomeLastFourAndCorrelationId() {
        String message = captureLog(this::enterPhone);
        assertTrue(message.matches("sms otp send SENT last4=0100 cid=[0-9a-f]{8}"), message);
        assertFalse(message.contains(PHONE), message);
        assertFalse(message.contains("real-secret"), message);
    }

    @Test
    void resendLogsResendSent() {
        enterPhone();
        String message = captureLog(() -> post("resend", "1"));
        assertTrue(message.matches("sms otp resend SENT last4=0100 cid=[0-9a-f]{8}"), message);
        assertFalse(message.contains(PHONE), message);
    }

    @Test
    void unavailableSendLogsUnavailable() {
        sendStatus = 503;
        sendError = "stub";
        authenticator.authenticate(context);
        String message = captureLog(() -> post("phone", PHONE));
        assertTrue(message.matches("sms otp send UNAVAILABLE last4=0100 cid=[0-9a-f]{8}"), message);
        assertFalse(message.contains(PHONE), message);
    }

    @Test
    void swallowedSendLogsSwallowedRuntimeException() {
        authenticator = new SmsOtpAuthenticator(new SmsOtpCalls(secret -> new SmsApi() {
            @Override
            public SentResponse send(SendRequest request) {
                throw new IllegalStateException("boom");
            }

            @Override
            public VerifiedResponse verify(VerifyRequest request) {
                throw new UnsupportedOperationException();
            }
        }), () -> Optional.of("real-secret"));
        authenticator.authenticate(context);
        String message = captureLog(() -> post("phone", PHONE));
        assertTrue(message.matches("sms otp send swallowed RuntimeException last4=0100 cid=[0-9a-f]{8}"), message);
        assertFalse(message.contains(PHONE), message);
        assertFalse(message.contains("boom"), message);
    }

    @Test
    void rateLimitedSendLogsRateLimited() {
        sendStatus = 429;
        sendError = "rate-limited";
        sendRetry = 900L;
        authenticator.authenticate(context);
        String message = captureLog(() -> post("phone", PHONE));
        assertTrue(message.matches("sms otp send RATE_LIMITED last4=0100 cid=[0-9a-f]{8}"), message);
        assertFalse(message.contains(PHONE), message);
    }

    private String captureLog(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getLogger(SmsOtpAuthenticator.class.getName());
        Level previous = log.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        log.setLevel(Level.INFO);
        log.addHandler(handler);
        try {
            action.run();
        } finally {
            log.removeHandler(handler);
            log.setLevel(previous);
        }
        assertEquals(1, records.size(), records.toString());
        assertNull(records.getFirst().getThrown());
        return records.getFirst().getMessage();
    }
}
