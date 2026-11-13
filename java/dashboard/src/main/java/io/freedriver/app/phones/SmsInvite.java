package io.freedriver.app.phones;

import io.freedriver.sms.api.SmsInviteApi;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The sms invite codes, called with the shared secret. */
@RegisterRestClient(configKey = "sms")
@RegisterClientHeaders(SmsSecretHeaders.class)
@RegisterProvider(SmsResponses.class)
public interface SmsInvite extends SmsInviteApi {
}
