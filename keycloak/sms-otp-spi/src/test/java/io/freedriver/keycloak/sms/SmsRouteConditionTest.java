package io.freedriver.keycloak.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsRouteConditionTest {

    private final Map<String, String> notes = new HashMap<>();
    private AuthenticationFlowContext context;
    private AuthenticatorConfigModel config;
    private final SmsRouteCondition condition = new SmsRouteCondition();

    @BeforeEach
    void setUp() {
        notes.clear();
        AuthenticationSessionModel authSession = mock(AuthenticationSessionModel.class);
        when(authSession.getAuthNote(anyString())).thenAnswer(inv -> notes.get(inv.<String>getArgument(0)));

        config = new AuthenticatorConfigModel();
        context = mock(AuthenticationFlowContext.class);
        when(context.getAuthenticationSession()).thenReturn(authSession);
        when(context.getAuthenticatorConfig()).thenAnswer(inv -> config);
        when(context.getUser()).thenThrow(new AssertionError("no user"));
        when(context.getSession()).thenThrow(new AssertionError("no session"));
        doThrow(new AssertionError("setUser")).when(context).setUser(any());
        doThrow(new AssertionError("success")).when(context).success();
        doThrow(new AssertionError("success")).when(context).success(anyString());
    }

    private void configured(String route) {
        config.getConfig().put(SmsRouteConditionFactory.CONF_ROUTE, route);
    }

    @Test
    void matchesOnlyTheConfiguredRouteNote() {
        configured(PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        assertTrue(condition.matchCondition(context));

        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_USERNAME);
        assertFalse(condition.matchCondition(context));

        notes.remove(PhoneUsernameRouter.NOTE_ROUTE);
        assertFalse(condition.matchCondition(context));

        notes.put(PhoneUsernameRouter.NOTE_ROUTE, "phone ");
        assertFalse(condition.matchCondition(context));
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, "Phone");
        assertFalse(condition.matchCondition(context));
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, "phone-extra");
        assertFalse(condition.matchCondition(context));

        configured(PhoneUsernameRouter.ROUTE_USERNAME);
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_USERNAME);
        assertTrue(condition.matchCondition(context));
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        assertFalse(condition.matchCondition(context));

        verify(context, never()).setUser(any());
        verify(context, never()).getUser();
        verify(context, never()).getSession();
        verify(context, never()).success();
    }

    @Test
    void missingOrUnexpectedConfigDoesNotMatch() {
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        config = null;
        assertFalse(condition.matchCondition(context));

        config = new AuthenticatorConfigModel();
        assertFalse(condition.matchCondition(context));

        configured("PHONE");
        assertFalse(condition.matchCondition(context));
        configured("phone username");
        assertFalse(condition.matchCondition(context));
        configured("");
        assertFalse(condition.matchCondition(context));

        config.setConfig(null);
        assertFalse(condition.matchCondition(context));
        verify(context, never()).success();
        verify(context, never()).setUser(any());
    }

    @Test
    void phoneNoteDoesNotSatisfyAUsernameCondition() {
        configured(PhoneUsernameRouter.ROUTE_USERNAME);
        notes.put(PhoneUsernameRouter.NOTE_ROUTE, PhoneUsernameRouter.ROUTE_PHONE);
        notes.put(PhoneUsernameRouter.NOTE_NUMBER, "+11234567890");
        assertFalse(condition.matchCondition(context));
    }

    @Test
    void factoryIsConfigurableAsPhoneOrUsername() {
        SmsRouteConditionFactory factory = new SmsRouteConditionFactory();
        assertEquals("freedriver-sms-route-condition", factory.getId());
        assertEquals("condition", factory.getReferenceCategory());
        assertTrue(factory.isConfigurable());
        assertFalse(factory.isUserSetupAllowed());
        List<AuthenticationExecutionModel.Requirement> choices = Arrays.asList(factory.getRequirementChoices());
        assertEquals(List.of(
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.DISABLED), choices);
        assertEquals(1, factory.getConfigProperties().size());
        assertEquals(SmsRouteConditionFactory.CONF_ROUTE, factory.getConfigProperties().get(0).getName());
        assertEquals(List.of(PhoneUsernameRouter.ROUTE_PHONE, PhoneUsernameRouter.ROUTE_USERNAME),
                factory.getConfigProperties().get(0).getOptions());
        Authenticator created = factory.create(null);
        assertTrue(created instanceof ConditionalAuthenticator);
        assertSame(factory.getSingleton(), created);
        assertFalse(((SmsRouteCondition) created).requiresUser());
    }
}
