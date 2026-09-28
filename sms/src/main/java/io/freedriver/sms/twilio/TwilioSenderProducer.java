package io.freedriver.sms.twilio;

import io.freedriver.sms.SmsConfig;
import io.freedriver.sms.phones.ConsentLedger;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.function.Function;

/** Builds the Twilio Verify sender from the environment. Runs at startup; makes no Twilio call. */
@ApplicationScoped
public class TwilioSenderProducer {

    private static final Logger LOG = Logger.getLogger(TwilioSenderProducer.class);

    @Produces
    @Singleton
    @LookupIfProperty(name = "freedriver.sms.sender", stringValue = "twilio", lookupIfMissing = true)
    TwilioVerifySender twilioSender(SmsConfig config, Config raw, ConsentLedger consents) {
        return create(name -> raw.getOptionalValue(name, String.class).orElse(null),
                config.twilio().baseUrl(), config.twilio().timeout(),
                LaunchMode.current() == LaunchMode.TEST, consents);
    }

    static TwilioVerifySender create(Function<String, String> env, String baseUrl, Duration timeout,
                                     boolean testProfile, ConsentLedger consents) {
        TwilioCredentials credentials = TwilioCredentials.load(env);
        TwilioEndpoint endpoint = TwilioEndpoint.resolve(baseUrl, testProfile);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("freedriver.sms.twilio.timeout must be positive");
        }
        LOG.info("Twilio credentials loaded");
        return new TwilioVerifySender(credentials, endpoint, timeout, consents);
    }
}
