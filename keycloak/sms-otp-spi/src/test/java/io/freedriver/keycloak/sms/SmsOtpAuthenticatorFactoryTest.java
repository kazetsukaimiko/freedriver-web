package io.freedriver.keycloak.sms;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SmsOtpAuthenticatorFactoryTest {

    @Test
    void smsIsAlternativeOnlySoItCannotBlockPassword() {
        SmsOtpAuthenticatorFactory factory = new SmsOtpAuthenticatorFactory();
        assertEquals("freedriver-sms-otp", factory.getId());
        assertFalse(Arrays.asList(factory.getRequirementChoices())
                .contains(AuthenticationExecutionModel.Requirement.REQUIRED));
        assertFalse(factory.isUserSetupAllowed());
    }
}
