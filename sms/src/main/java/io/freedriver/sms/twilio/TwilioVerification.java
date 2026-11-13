package io.freedriver.sms.twilio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The two fields sms reads from a Verify answer. Either may be absent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TwilioVerification(String sid, String status) {
}
