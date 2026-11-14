package io.freedriver.app.phones;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import java.util.Optional;

/** Adds the sms shared secret to every sms call. The secret never goes to the browser. */
@ApplicationScoped
public class SmsSecretHeaders implements ClientHeadersFactory {

    static final String HEADER = "X-Freedriver-Sms-Secret";

    private final String secret;

    @Inject
    SmsSecretHeaders(@ConfigProperty(name = "sms.otp.shared-secret") Optional<String> secret) {
        this.secret = secret.map(String::strip).orElse("");
    }

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                 MultivaluedMap<String, String> outgoing) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle(HEADER, secret);
        return headers;
    }
}
