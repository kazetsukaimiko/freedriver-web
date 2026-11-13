package io.freedriver.sms.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

import java.io.IOException;

/**
 * Twilio answered 400 or more. Carries the status and Twilio's numeric error code only; the
 * message text can echo the number, so it is never kept.
 */
final class TwilioRefused extends RuntimeException {

    private final int status;
    private final String twilioError;

    TwilioRefused(int status, String twilioError) {
        super("Twilio answered " + status, null, false, false);
        this.status = status;
        this.twilioError = twilioError;
    }

    int status() {
        return status;
    }

    /** The numeric Twilio error code, or {@code none}. */
    String twilioError() {
        return twilioError;
    }

    /** Maps a Twilio status of 400 or more to {@link TwilioRefused}. */
    static final class Mapper implements ResponseExceptionMapper<TwilioRefused> {

        private static final int MAX_BODY = 16 * 1024;
        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public TwilioRefused toThrowable(Response response) {
            return new TwilioRefused(response.getStatus(), code(response));
        }

        private static String code(Response response) {
            try {
                String body = response.hasEntity() ? response.readEntity(String.class) : null;
                if (body == null || body.isBlank() || body.length() > MAX_BODY) {
                    return "none";
                }
                JsonNode node = JSON.readTree(body);
                JsonNode code = node != null && node.isObject() ? node.get("code") : null;
                return code != null && code.isNumber() ? code.asText() : "none";
            } catch (IOException | RuntimeException unreadable) {
                return "none";
            }
        }
    }
}
