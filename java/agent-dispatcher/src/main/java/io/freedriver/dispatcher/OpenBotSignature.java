package io.freedriver.dispatcher;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;

/**
 * Signs a webhook request: HmacSHA256 over {@code timestamp.deliveryId.body}. The filter runs
 * before the entity is written, so the entity must already be the {@code byte[]} that is sent.
 */
final class OpenBotSignature implements ClientRequestFilter {

    private final String secret;

    OpenBotSignature(String secret) {
        this.secret = secret;
    }

    @Override
    public void filter(ClientRequestContext request) {
        if (!(request.getEntity() instanceof byte[] body)) {
            throw new IllegalStateException("webhook body must be serialized bytes");
        }
        String timestamp = request.getHeaderString(OpenBotWebhook.TIMESTAMP);
        String deliveryId = request.getHeaderString(OpenBotWebhook.DELIVERY_ID);
        request.getHeaders().putSingle(OpenBotWebhook.SIGNATURE, sign(secret, timestamp, deliveryId, body));
    }

    static String sign(String secret, String timestamp, String deliveryId, byte[] body) {
        byte[] prefix = (timestamp + "." + deliveryId + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + hex(mac.doFinal(message));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is unavailable");
        }
    }

    private static String hex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }
}
