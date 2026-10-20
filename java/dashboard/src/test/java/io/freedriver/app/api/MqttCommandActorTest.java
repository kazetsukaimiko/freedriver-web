package io.freedriver.app.api;

import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MqttCommandActorTest {

    @Test
    void actor_is_the_principal_name() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("kaze"))
                .addRole("dashboard")
                .build();
        assertEquals("kaze", MqttCommandActor.actor(identity));
    }

    @Test
    void actor_is_null_when_the_caller_is_missing_or_blank() {
        assertNull(MqttCommandActor.actor(null));
        assertNull(MqttCommandActor.actor(QuarkusSecurityIdentity.builder().setAnonymous(true).build()));
        assertNull(MqttCommandActor.actor(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("  "))
                .addRole("dashboard")
                .build()));
        assertNull(MqttCommandActor.actor(QuarkusSecurityIdentity.builder()
                .setPrincipal(new Principal() {
                    @Override
                    public String getName() {
                        throw new IllegalStateException("context not active");
                    }
                })
                .addRole("dashboard")
                .build()));
    }
}
