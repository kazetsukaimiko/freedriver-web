package io.freedriver.app.phones;

import io.freedriver.app.api.LoginLinks;
import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.sms.api.SmsInviteApi;
import io.freedriver.sms.api.SmsPhonesApi;
import io.freedriver.sms.model.AddPhone;
import io.freedriver.sms.model.Added;
import io.freedriver.sms.model.AttachUser;
import io.freedriver.sms.model.Attached;
import io.freedriver.sms.model.InviteSend;
import io.freedriver.sms.model.PhoneList;
import io.freedriver.sms.model.RemovePhone;
import io.freedriver.sms.model.Removed;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.model.WhitelistPhone;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneRemovalBlankSecretTest {

    @Test
    void blank_secret_throws_before_keycloak_and_the_http_client() {
        for (String secret : new String[] {null, "", " ", "\t", " \n "}) {
            CountingSms http = new CountingSms();
            HttpSmsPhoneList sms = new HttpSmsPhoneList(http, http, secret);
            java.util.concurrent.atomic.AtomicInteger keycloak = new java.util.concurrent.atomic.AtomicInteger();
            PhoneSignInDirectory directory = phone -> {
                keycloak.incrementAndGet();
                return true;
            };
            PhoneRemovalResource resource = new PhoneRemovalResource(sms, directory, new LoginLinks("https://app.freedriver.io"));
            InviteUpstreamException error = assertThrows(InviteUpstreamException.class,
                    () -> resource.remove(new PhoneRemovalResource.RemovePhone("+15555550123")),
                    "secret [" + secret + "]");
            assertEquals("sms shared secret is unset", error.getMessage());
            assertEquals(0, keycloak.get());
            assertEquals(0, http.calls);
            assertThrows(InviteUpstreamException.class, sms::list);
            assertThrows(InviteUpstreamException.class, () -> sms.remove("+15555550123"));
            assertThrows(InviteUpstreamException.class, () -> sms.sendInviteCode("+15555550123", UUID.randomUUID()));
            assertThrows(InviteUpstreamException.class, () -> sms.checkInviteCode("+15555550123", "123456"));
            assertThrows(InviteUpstreamException.class, () -> sms.whitelist("+15555550123", null));
            assertEquals(0, http.calls);
        }
    }

    @Test
    void configured_secret_still_calls_keycloak_before_the_http_client() {
        CountingSms http = new CountingSms();
        HttpSmsPhoneList sms = new HttpSmsPhoneList(http, http, "secret-value");
        java.util.concurrent.atomic.AtomicInteger keycloak = new java.util.concurrent.atomic.AtomicInteger();
        PhoneSignInDirectory directory = phone -> {
            assertEquals(0, http.calls);
            keycloak.incrementAndGet();
            return true;
        };
        PhoneRemovalResource resource = new PhoneRemovalResource(sms, directory, new LoginLinks("https://app.freedriver.io"));
        Response response = resource.remove(new PhoneRemovalResource.RemovePhone("+15555550123"));
        try {
            assertEquals(204, response.getStatus());
        } finally {
            response.close();
        }
        assertEquals(1, keycloak.get());
        assertEquals(1, http.calls);
    }

    @Test
    void sms_statuses_become_the_invite_answers() {
        CountingSms http = new CountingSms();
        HttpSmsPhoneList sms = new HttpSmsPhoneList(http, http, "secret-value");

        http.refuse = 409;
        assertEquals(SmsPhoneList.InviteText.ALREADY_LISTED, sms.sendInviteCode("+15555550123", UUID.randomUUID()));
        http.refuse = 429;
        assertEquals(SmsPhoneList.InviteText.LIMITED, sms.sendInviteCode("+15555550123", UUID.randomUUID()));
        assertEquals(SmsPhoneList.InviteCheck.LIMITED, sms.checkInviteCode("+15555550123", "123456"));
        http.refuse = 400;
        assertEquals(SmsPhoneList.InviteCheck.WRONG, sms.checkInviteCode("+15555550123", "123456"));
        http.refuse = 503;
        assertEquals(SmsPhoneList.InviteText.UNAVAILABLE, sms.sendInviteCode("+15555550123", UUID.randomUUID()));
        assertEquals(SmsPhoneList.InviteCheck.UNAVAILABLE, sms.checkInviteCode("+15555550123", "123456"));
        InviteUpstreamException add = assertThrows(InviteUpstreamException.class,
                () -> sms.addInvited("+15555550123", "Ada", "inv" + "0".repeat(32)));
        assertEquals("sms answered 503", add.getMessage());

        http.refuse = 0;
        assertEquals(SmsPhoneList.InviteText.SENT, sms.sendInviteCode("+15555550123", UUID.randomUUID()));
        assertEquals(SmsPhoneList.InviteCheck.APPROVED, sms.checkInviteCode("+15555550123", "123456"));
    }

    @Test
    void a_transport_failure_is_an_upstream_failure() {
        CountingSms http = new CountingSms();
        http.broken = true;
        HttpSmsPhoneList sms = new HttpSmsPhoneList(http, http, "secret-value");
        InviteUpstreamException error = assertThrows(InviteUpstreamException.class, sms::list);
        assertEquals("sms phone list failed", error.getMessage());
    }

    /** Both sms clients. {@code refuse} is the status the mapper would turn into {@link SmsRefused}. */
    private static final class CountingSms implements SmsPhonesApi, SmsInviteApi {
        int calls;
        int refuse;
        boolean broken;

        private void answer() {
            calls++;
            if (broken) {
                throw new jakarta.ws.rs.ProcessingException("connection refused");
            }
            if (refuse != 0) {
                throw new SmsRefused(refuse);
            }
        }

        @Override
        public PhoneList list() {
            answer();
            return new PhoneList(List.of());
        }

        @Override
        public Removed remove(RemovePhone request) {
            answer();
            return new Removed(true);
        }

        @Override
        public Added add(AddPhone request) {
            answer();
            return new Added(true);
        }

        @Override
        public Added whitelist(WhitelistPhone request) {
            answer();
            return new Added(true);
        }

        @Override
        public Attached attach(AttachUser request) {
            answer();
            return new Attached(true);
        }

        @Override
        public Response send(InviteSend request) {
            answer();
            return Response.ok().build();
        }

        @Override
        public VerifiedResponse verify(VerifyRequest request) {
            answer();
            return VerifiedResponse.otp(request.phone());
        }
    }
}
