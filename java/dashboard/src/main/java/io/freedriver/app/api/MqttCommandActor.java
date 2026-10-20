package io.freedriver.app.api;

import io.freedriver.app.appliances.ApplianceCommandRouted;
import io.freedriver.app.appliances.MqttLiveClient;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.security.Principal;

/** Reads the request principal and passes that name to {@link MqttLiveClient}. */
@ApplicationScoped
public class MqttCommandActor {

    private final SecurityIdentity identity;
    private final MqttLiveClient mqtt;

    @Inject
    public MqttCommandActor(SecurityIdentity identity, MqttLiveClient mqtt) {
        this.identity = identity;
        this.mqtt = mqtt;
    }

    void onCommand(@Observes ApplianceCommandRouted routed) {
        if (!mqtt.acceptsCommands()) {
            return;
        }
        mqtt.onCommand(routed, actor(identity));
    }

    static String actor(SecurityIdentity identity) {
        try {
            if (identity == null || identity.isAnonymous()) {
                return null;
            }
            Principal principal = identity.getPrincipal();
            if (principal == null) {
                return null;
            }
            String name = principal.getName();
            if (name == null || name.isBlank()) {
                return null;
            }
            return name;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
