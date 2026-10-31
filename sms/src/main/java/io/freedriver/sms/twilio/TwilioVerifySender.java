package io.freedriver.sms.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.otp.AgreementGatedSender;
import io.freedriver.sms.otp.CheckOutcome;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.otp.SendResult;
import io.freedriver.sms.otp.VerificationSid;
import io.freedriver.sms.phones.ConsentLedger;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

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
import java.util.stream.Collectors;

/**
 * Twilio Verify client. It creates a verification (Twilio generates and texts the code), checks
 * a code, and cancels a verification by setting its status to {@code canceled}. It never sends
 * its own code, never stores or logs a code, and logs failures with the number masked to its last
 * two digits. The JDK HTTP client it uses has no request or response logging switched on.
 */
public class TwilioVerifySender extends AgreementGatedSender {

    private static final Logger LOG = Logger.getLogger(TwilioVerifySender.class);
    static final String MDC_CID = "smsCid";
    private static final int MAX_BODY = 16 * 1024;

    private final TwilioEndpoint endpoint;
    private final String serviceSid;
    private final String authorization;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public TwilioVerifySender(TwilioCredentials credentials, TwilioEndpoint endpoint, Duration timeout,
                              ConsentLedger consents) {
        super(consents);
        this.endpoint = endpoint;
        this.serviceSid = credentials.verifyServiceSid();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (credentials.apiKeySid() + ":" + credentials.apiKeySecret()).getBytes(StandardCharsets.UTF_8));
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected SendResult deliver(String phone) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", phone);
        form.put("Channel", "sms");
        String masked = PhoneMask.mask(phone);
        try {
            HttpResponse<String> response = post(endpoint.verifications(serviceSid), form);
            if (response.statusCode() == 201 || response.statusCode() == 200) {
                String sid = field(response.body(), "sid");
                String cid = correlationId();
                if (!VerificationSid.matches(sid)) {
                    LOG.warnf("Twilio accepted the code send to %s without a verification sid cid=%s",
                            masked, cid);
                    return SendResult.of(SendOutcome.SENT);
                }
                LOG.infof("Twilio accepted the code send to %s sid=%s cid=%s",
                        masked, VerificationSid.maskForLog(sid), cid);
                return new SendResult(SendOutcome.SENT, sid);
            }
            LOG.warnf("Twilio refused the code send to %s (HTTP %d, Twilio error %s)",
                    masked, response.statusCode(), twilioError(response.body()));
        } catch (HttpTimeoutException e) {
            LOG.warnf("Twilio timed out sending a code to %s after %s", masked, timeout);
        } catch (IOException e) {
            LOG.warnf("Twilio code send to %s failed: %s", masked, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Twilio code send to %s was interrupted", masked);
        }
        return SendResult.of(SendOutcome.FAILED);
    }

    @Override
    public boolean cancelVerification(String verificationSid) {
        if (!VerificationSid.matches(verificationSid)) {
            return false;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("Status", "canceled");
        try {
            HttpResponse<String> response = post(endpoint.verification(serviceSid, verificationSid), form);
            int status = response.statusCode();
            if (status == 200 || status == 201 || status == 404) {
                return true;
            }
            LOG.warnf("Twilio did not cancel a verification (HTTP %d, Twilio error %s)",
                    status, twilioError(response.body()));
        } catch (HttpTimeoutException e) {
            LOG.warnf("Twilio timed out canceling a verification after %s", timeout);
        } catch (IOException e) {
            LOG.warnf("Twilio cancel failed: %s", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Twilio cancel was interrupted");
        }
        return false;
    }

    @Override
    public CheckOutcome checkCode(String phone, String code) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", phone);
        form.put("Code", code);
        String masked = PhoneMask.mask(phone);
        try {
            HttpResponse<String> response = post(endpoint.verificationCheck(serviceSid), form);
            int status = response.statusCode();
            if (status == 200) {
                return "approved".equals(field(response.body(), "status"))
                        ? CheckOutcome.APPROVED
                        : CheckOutcome.WRONG_CODE;
            }
            if (status == 429 && "60202".equals(twilioError(response.body()))) {
                LOG.infof("Twilio check attempt limit reached for %s (HTTP %d, Twilio error 60202)",
                        masked, status);
                return CheckOutcome.TOO_MANY_ATTEMPTS;
            }
            if (status == 404 || status == 429) {
                // No pending verification (expired, already used) or out of check attempts.
                LOG.infof("Twilio has no open verification for %s (HTTP %d, Twilio error %s)",
                        masked, status, twilioError(response.body()));
                return CheckOutcome.WRONG_CODE;
            }
            LOG.warnf("Twilio refused the code check for %s (HTTP %d, Twilio error %s)",
                    masked, status, twilioError(response.body()));
        } catch (HttpTimeoutException e) {
            LOG.warnf("Twilio timed out checking a code for %s after %s", masked, timeout);
        } catch (IOException e) {
            LOG.warnf("Twilio code check for %s failed: %s", masked, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Twilio code check for %s was interrupted", masked);
        }
        return CheckOutcome.FAILED;
    }

    private HttpResponse<String> post(URI uri, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String correlationId() {
        Object value = MDC.get(MDC_CID);
        return value == null || value.toString().isBlank() ? "-" : value.toString();
    }

    /** Twilio's numeric error code only; the message text can echo the number. */
    private String twilioError(String body) {
        JsonNode node = tree(body);
        JsonNode code = node == null ? null : node.get("code");
        return code != null && code.isNumber() ? code.asText() : "none";
    }

    private String field(String body, String name) {
        JsonNode node = tree(body);
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private JsonNode tree(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }
}
