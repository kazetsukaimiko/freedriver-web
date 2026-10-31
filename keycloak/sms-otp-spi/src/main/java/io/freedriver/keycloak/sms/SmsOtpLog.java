package io.freedriver.keycloak.sms;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Send/resend INFO lines. Last four digits match {@link PhoneUsernameRouter#phoneLog}.
 * Never logs an E.164 number, a code, or the shared secret.
 */
final class SmsOtpLog {

    static final Logger LOG = Logger.getLogger(SmsOtpAuthenticator.class.getName());

    private SmsOtpLog() {
    }

    static String newCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static String last4(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return phone.substring(phone.length() - 4);
    }

    static void send(boolean resend, SmsOtpCalls.Outcome outcome, String phone, String cid) {
        LOG.info("sms otp " + (resend ? "resend" : "send") + " " + label(outcome)
                + " last4=" + last4(phone) + " cid=" + cid);
    }

    private static String label(SmsOtpCalls.Outcome outcome) {
        if (outcome == SmsOtpCalls.Outcome.SENT) {
            return "SENT";
        }
        if (outcome == SmsOtpCalls.Outcome.RATE_LIMITED) {
            return "RATE_LIMITED";
        }
        if (outcome == SmsOtpCalls.Outcome.SWALLOWED) {
            return "swallowed RuntimeException";
        }
        return "UNAVAILABLE";
    }
}
