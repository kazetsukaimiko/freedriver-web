package io.freedriver.sms.otp;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The sender chosen by {@code freedriver.sms.sender}. It is built at startup, so missing or bad
 * provider settings stop the service before it serves a request.
 */
@ApplicationScoped
public class ActiveSender {

    private static final Logger LOG = Logger.getLogger(ActiveSender.class);

    private final Instance<SmsSender> senders;
    private volatile SmsSender sender;

    @Inject
    public ActiveSender(Instance<SmsSender> senders) {
        this.senders = senders;
    }

    void onStart(@Observes StartupEvent event) {
        SmsSender chosen = get();
        LOG.infof("SMS sender ready: %s", chosen.getClass().getSimpleName());
    }

    public SmsSender get() {
        SmsSender current = sender;
        if (current == null) {
            synchronized (this) {
                if (sender == null) {
                    sender = senders.get();
                }
                current = sender;
            }
        }
        return current;
    }

    /** True once the sender is built. */
    public boolean ready() {
        return sender != null;
    }
}
