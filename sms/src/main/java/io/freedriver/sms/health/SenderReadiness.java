package io.freedriver.sms.health;

import io.freedriver.sms.otp.ActiveSender;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** {@code /health} and {@code /health/ready} answer 200 only once the sender (Twilio client) is configured. */
@Readiness
@ApplicationScoped
public class SenderReadiness implements HealthCheck {

    private final ActiveSender sender;

    @Inject
    public SenderReadiness(ActiveSender sender) {
        this.sender = sender;
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("sms-sender").status(sender.ready()).build();
    }
}
