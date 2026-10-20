package io.freedriver.app.appliances;

import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.InstanceView;
import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceServiceTest {

    private static final UUID INSTANCE = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void currentMap_empty_has_no_instances() {
        ApplianceControl control = new ApplianceControl();
        ApplianceMapResponse map = service(control).currentMap();
        assertTrue(map.instances().isEmpty());
    }

    @Test
    void issueCommand_unknown_instance_does_not_publish() {
        RecordingControl control = RecordingControl.withCabin(false);
        control.known = false;
        ApplianceInstanceNotFoundException thrown = assertThrows(
                ApplianceInstanceNotFoundException.class,
                () -> service(control).issueCommand(INSTANCE, "hallway", true, null));
        assertEquals(INSTANCE.toString(), thrown.getMessage());
        assertTrue(control.published.isEmpty());
    }

    @Test
    void issueCommand_stale_does_not_publish() {
        RecordingControl control = RecordingControl.withCabin(true);
        control.snapshot = new ApplianceSnapshot(
                Instant.now().minusSeconds(30),
                List.of(new Appliance("hallway", true)));
        ApplianceStaleException thrown = assertThrows(
                ApplianceStaleException.class,
                () -> service(control).issueCommand(INSTANCE, "hallway", false, null));
        assertEquals(INSTANCE, thrown.instanceId());
        assertTrue(control.published.isEmpty());
    }

    @Test
    void issueCommand_unknown_appliance_is_not_found() {
        RecordingControl control = RecordingControl.withCabin(false);
        ApplianceNotFoundException thrown = assertThrows(
                ApplianceNotFoundException.class,
                () -> service(control).issueCommand(INSTANCE, "attic", true, null));
        assertEquals("attic", thrown.getMessage());
        assertTrue(control.published.isEmpty());
    }

    @Test
    void issueCommand_confirm_updates_view() {
        RecordingControl control = RecordingControl.withCabin(false);
        control.confirm = true;
        InstanceView view = service(control).issueCommand(INSTANCE, "hallway", true, null);
        assertFalse(view.timeout());
        assertFalse(view.stale());
        assertEquals(1, control.published.size());
        assertTrue(view.appliances().getFirst().on());
    }

    @Test
    void issueCommand_timeout_is_200_shape_not_an_exception() {
        RecordingControl control = RecordingControl.withCabin(false);
        control.confirm = false;
        InstanceView view = service(control).issueCommand(INSTANCE, "hallway", true, null);
        assertTrue(view.timeout());
        assertFalse(view.stale());
        assertFalse(view.appliances().getFirst().on());
        assertEquals(1, control.published.size());
    }

    @Test
    void issueCommand_records_the_subject_on_confirm_and_timeout() {
        CapturingAudit audit = new CapturingAudit();
        RecordingControl confirmed = RecordingControl.withCabin(false);
        confirmed.confirm = true;
        service(confirmed, audit).issueCommand(INSTANCE, "hallway", true, "caller-sub-219");
        assertEquals("caller-sub-219", audit.last.subject());
        assertEquals("confirmed", audit.last.result());
        assertEquals(
                "audit appliance when=" + audit.last.when()
                        + " instance=" + INSTANCE
                        + " appliance=hallway on=true commandId=" + audit.last.commandId()
                        + " result=confirmed subject=caller-sub-219",
                ApplianceAudit.line(audit.last));

        RecordingControl timedOut = RecordingControl.withCabin(false);
        timedOut.confirm = false;
        service(timedOut, audit).issueCommand(INSTANCE, "hallway", true, "  ");
        assertNull(audit.last.subject());
        assertEquals("timeout", audit.last.result());
        assertTrue(ApplianceAudit.line(audit.last).endsWith(" subject="));
    }

    private static ApplianceService service(ApplianceControl control) {
        return service(control, new ApplianceAudit());
    }

    private static ApplianceService service(ApplianceControl control, ApplianceAudit audit) {
        AppliancesConfig config = new AppliancesConfig();
        config.staleAfter = Duration.ofSeconds(20);
        config.commandTimeout = Duration.ofMillis(50);
        config.commandTimeoutMax = Duration.ofSeconds(30);
        return new ApplianceService(config, control, audit);
    }

    private static final class CapturingAudit extends ApplianceAudit {
        private ApplianceAudit.Event last;

        @Override
        public void record(ApplianceAudit.Event event) {
            last = event;
        }
    }

    /** Test double. @Vetoed keeps it out of CDI, so ApplianceControl stays the single bean. */
    @Vetoed
    private static final class RecordingControl extends ApplianceControl {
        private boolean known = true;
        private boolean confirm = true;
        private ApplianceSnapshot snapshot;
        private final java.util.List<ApplianceCommandMessage> published = new java.util.ArrayList<>();

        static RecordingControl withCabin(boolean on) {
            RecordingControl control = new RecordingControl();
            control.snapshot = new ApplianceSnapshot(Instant.now(), List.of(new Appliance("hallway", on)));
            return control;
        }

        @Override
        public java.util.Optional<String> instanceName(UUID instanceId) {
            return known ? java.util.Optional.of("Cabin") : java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ApplianceSnapshot> instanceSnapshot(UUID instanceId) {
            return known ? java.util.Optional.of(snapshot) : java.util.Optional.empty();
        }

        @Override
        public boolean publishCommand(UUID instanceId, ApplianceCommandMessage command) {
            if (!known) {
                return false;
            }
            published.add(command);
            if (confirm) {
                snapshot = new ApplianceSnapshot(
                        Instant.now(), List.of(new Appliance(command.applianceName(), command.state())));
            }
            return true;
        }

        @Override
        public java.util.Optional<ApplianceSnapshot> publishCommandAndWait(
                UUID instanceId, ApplianceCommandMessage command, Duration timeout) {
            if (!publishCommand(instanceId, command) || !confirm) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(snapshot);
        }

        @Override
        public java.util.Map<UUID, ApplianceSnapshot> allKnownInstances() {
            return known ? java.util.Map.of(INSTANCE, snapshot) : java.util.Map.of();
        }

        @Override
        public java.util.Map<UUID, String> instanceNames() {
            return known ? java.util.Map.of(INSTANCE, "Cabin") : java.util.Map.of();
        }
    }
}
