package io.freedriver.sms.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.invite.InvitationSender;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.validation.UsPhoneNumber;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One Twilio Messages API call. The Verify API cannot carry a login link, so invitation texts
 * use {@code POST /2010-04-01/Accounts/{AccountSid}/Messages.json}. The From number is optional
 * at startup; a missing one leaves this sender unconfigured.
 */
public class TwilioMessagesSender implements InvitationSender {

    static final URI PRODUCTION = URI.create("https://api.twilio.com");
    static final String FROM = "TWILIO_MESSAGING_FROM";

    private static final Logger LOG = Logger.getLogger(TwilioMessagesSender.class);
    private static final int MAX_BODY = 16 * 1024;
    private static final Pattern FROM_NUMBER = Pattern.compile(UsPhoneNumber.REGEX);

    private final String from;
    private final String authorization;
    private final URI messages;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public TwilioMessagesSender(TwilioCredentials credentials, String from, Duration timeout) {
        this(credentials, from, timeout, PRODUCTION);
    }

    TwilioMessagesSender(TwilioCredentials credentials, String from, Duration timeout, URI base) {
        this.from = from;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (credentials.apiKeySid() + ":" + credentials.apiKeySecret()).getBytes(StandardCharsets.UTF_8));
        String root = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1) : base.toString();
        this.messages = URI.create(root + "/2010-04-01/Accounts/" + credentials.accountSid() + "/Messages.json");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** A blank value is unconfigured. Any other value that is not {@code +1} and ten digits stops startup. */
    public static String fromNumber(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (!FROM_NUMBER.matcher(value).matches()) {
            throw new IllegalStateException("Malformed: " + FROM + ".");
        }
        return value;
    }

    @Override
    public boolean configured() {
        return from != null;
    }

    /** The Messages URL this sender posts to. */
    URI endpoint() {
        return messages;
    }

    @Override
    public SendOutcome send(String phone, String body) {
        if (from == null) {
            return SendOutcome.FAILED;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", phone);
        form.put("From", from);
        form.put("Body", body);
        String masked = PhoneMask.mask(phone);
        try {
            HttpResponse<String> response = post(form);
            int status = response.statusCode();
            if (status == 201 || status == 200) {
                return SendOutcome.SENT;
            }
            LOG.warnf("Twilio refused the invitation text to %s (HTTP %d, Twilio error %s)",
                    masked, status, twilioError(response.body()));
        } catch (HttpTimeoutException e) {
            LOG.warnf("Twilio timed out sending an invitation text to %s after %s", masked, timeout);
        } catch (IOException e) {
            LOG.warnf("Twilio invitation text to %s failed: %s", masked, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Twilio invitation text to %s was interrupted", masked);
        }
        return SendOutcome.FAILED;
    }

    private HttpResponse<String> post(Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(messages)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Twilio's numeric error code only; the message text can echo the number. */
    private String twilioError(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY) {
            return "none";
        }
        try {
            JsonNode node = json.readTree(body);
            JsonNode code = node == null ? null : node.get("code");
            return code != null && code.isNumber() ? code.asText() : "none";
        } catch (IOException e) {
            return "none";
        }
    }
}
