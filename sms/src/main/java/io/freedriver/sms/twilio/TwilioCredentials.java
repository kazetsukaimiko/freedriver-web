package io.freedriver.sms.twilio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The four Twilio Verify settings from the server environment (#170). The API key SID and secret
 * authenticate every call; Verify v2 URLs carry the service SID and not the account SID, so the
 * account SID is checked here and not sent. {@link #toString()} never prints a value.
 */
public record TwilioCredentials(String accountSid, String apiKeySid, String apiKeySecret, String verifyServiceSid) {

    public static final String ACCOUNT_SID = "TWILIO_ACCOUNT_SID";
    public static final String API_KEY_SID = "TWILIO_API_KEY_SID";
    public static final String API_KEY_SECRET = "TWILIO_API_KEY_SECRET";
    public static final String VERIFY_SERVICE_SID = "TWILIO_VERIFY_SERVICE_SID";

    private static final Pattern ACCOUNT = Pattern.compile("^AC[0-9a-fA-F]{32}$");
    private static final Pattern API_KEY = Pattern.compile("^SK[0-9a-fA-F]{32}$");
    private static final Pattern SERVICE = Pattern.compile("^VA[0-9a-fA-F]{32}$");

    /**
     * Reads the four settings by name. Any missing, blank or malformed value stops startup with a
     * message that names the settings and prints none of their values. Makes no network call.
     */
    public static TwilioCredentials load(Function<String, String> lookup) {
        List<String> missing = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        String account = read(lookup, ACCOUNT_SID, ACCOUNT, missing, malformed);
        String keySid = read(lookup, API_KEY_SID, API_KEY, missing, malformed);
        String keySecret = read(lookup, API_KEY_SECRET, null, missing, malformed);
        String service = read(lookup, VERIFY_SERVICE_SID, SERVICE, missing, malformed);
        if (!missing.isEmpty() || !malformed.isEmpty()) {
            StringBuilder message = new StringBuilder("Twilio Verify settings are not usable.");
            if (!missing.isEmpty()) {
                message.append(" Missing: ").append(String.join(", ", missing)).append('.');
            }
            if (!malformed.isEmpty()) {
                message.append(" Malformed: ").append(String.join(", ", malformed)).append('.');
            }
            throw new IllegalStateException(message.toString());
        }
        return new TwilioCredentials(account, keySid, keySecret, service);
    }

    private static String read(Function<String, String> lookup, String name, Pattern shape,
                               List<String> missing, List<String> malformed) {
        String raw = lookup.apply(name);
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            missing.add(name);
            return null;
        }
        if (shape != null && !shape.matcher(value).matches()) {
            malformed.add(name);
            return null;
        }
        return value;
    }

    @Override
    public String toString() {
        return "TwilioCredentials[redacted]";
    }
}
