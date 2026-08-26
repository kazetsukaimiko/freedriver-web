package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplianceServiceTest {

    private static final UUID INSTANCE = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void currentMap_absent_is_stale_empty() {
        MemoryBackend backend = new MemoryBackend();
        ApplianceMapResponse map = service(backend).currentMap();
        assertTrue(map.stale());
        assertFalse(map.timeout());
        assertEquals(null, map.lastUpdated());
        assertTrue(map.appliances().isEmpty());
    }

    @Test
    void issueCommand_absent_is_stale_and_does_not_publish() {
        MemoryBackend backend = new MemoryBackend();
        ApplianceStaleException thrown = assertThrows(
                ApplianceStaleException.class,
                () -> service(backend).issueCommand("living-room-lamp", true));
        assertTrue(thrown.snapshot().isEmpty());
        assertTrue(backend.publishedCommands().isEmpty());
    }

    @Test
    void issueCommand_stale_carries_snapshot_and_does_not_publish() {
        MemoryBackend backend = new MemoryBackend();
        ApplianceSnapshot stale = new ApplianceSnapshot(
                Instant.now().minusSeconds(30),
                INSTANCE,
                "Cabin",
                null,
                List.of(new Appliance("living-room-lamp", true)));
        backend.setSnapshot(stale);

        ApplianceStaleException thrown = assertThrows(
                ApplianceStaleException.class,
                () -> service(backend).issueCommand("living-room-lamp", false));
        assertEquals(stale, thrown.snapshot().orElseThrow());
        assertTrue(backend.publishedCommands().isEmpty());
    }

    @Test
    void issueCommand_unknown_appliance_is_not_found() {
        MemoryBackend backend = freshLamp(false);
        ApplianceNotFoundException thrown = assertThrows(
                ApplianceNotFoundException.class,
                () -> service(backend).issueCommand("kitchen-toaster", true));
        assertEquals("kitchen-toaster", thrown.getMessage());
        assertTrue(backend.publishedCommands().isEmpty());
    }

    @Test
    void issueCommand_missing_instanceId_on_fresh_map_is_corrupt() {
        MemoryBackend backend = new MemoryBackend();
        backend.setSnapshot(new ApplianceSnapshot(
                Instant.now(),
                null,
                "Cabin",
                null,
                List.of(new Appliance("living-room-lamp", false))));
        assertThrows(
                IllegalStateException.class,
                () -> service(backend).issueCommand("living-room-lamp", true));
        assertTrue(backend.publishedCommands().isEmpty());
    }

    @Test
    void issueCommand_confirm_updates_map() {
        MemoryBackend backend = freshLamp(false);
        backend.confirm = true;
        ApplianceMapResponse map = service(backend).issueCommand("living-room-lamp", true);
        assertFalse(map.timeout());
        assertFalse(map.stale());
        assertEquals(1, backend.publishedCommands().size());
        assertTrue(map.appliances().getFirst().on());
    }

    @Test
    void issueCommand_timeout_is_200_shape_not_an_exception() {
        MemoryBackend backend = freshLamp(false);
        backend.confirm = false;
        ApplianceMapResponse map = service(backend).issueCommand("living-room-lamp", true);
        assertTrue(map.timeout());
        assertFalse(map.stale());
        assertFalse(map.appliances().getFirst().on());
        assertEquals(1, backend.publishedCommands().size());
    }

    private static ApplianceService service(ApplianceBackend backend) {
        AppliancesConfig config = new AppliancesConfig();
        config.staleAfter = Duration.ofSeconds(20);
        config.commandTimeout = Duration.ofMillis(50);
        config.commandTimeoutMax = Duration.ofSeconds(30);
        return new ApplianceService(config, backend, new ApplianceAudit());
    }

    private static MemoryBackend freshLamp(boolean on) {
        MemoryBackend backend = new MemoryBackend();
        backend.setSnapshot(new ApplianceSnapshot(
                Instant.now(),
                INSTANCE,
                "Cabin",
                null,
                List.of(new Appliance("living-room-lamp", on))));
        return backend;
    }

    private static final class MemoryBackend implements ApplianceBackend {
        private ApplianceSnapshot snapshot;
        private boolean confirm = true;
        private final List<ApplianceCommandMessage> commands = new CopyOnWriteArrayList<>();

        void setSnapshot(ApplianceSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<ApplianceSnapshot> snapshot() {
            return Optional.ofNullable(snapshot);
        }

        @Override
        public void publishCommand(ApplianceCommandMessage command) {
            commands.add(command);
            if (!confirm || snapshot == null) {
                return;
            }
            List<Appliance> next = new ArrayList<>();
            for (Appliance appliance : snapshot.appliances()) {
                if (appliance.applianceName().equals(command.applianceName())) {
                    next.add(new Appliance(appliance.applianceName(), command.on()));
                } else {
                    next.add(appliance);
                }
            }
            snapshot = new ApplianceSnapshot(
                    Instant.now(),
                    snapshot.instanceId(),
                    snapshot.instanceName(),
                    command.commandId(),
                    next);
        }

        @Override
        public Optional<ApplianceSnapshot> awaitApplied(String commandId, Duration timeout) {
            if (snapshot != null && commandId.equals(snapshot.appliedCommandId())) {
                return Optional.of(snapshot);
            }
            return Optional.empty();
        }

        @Override
        public List<ApplianceCommandMessage> publishedCommands() {
            return List.copyOf(commands);
        }
    }
}
