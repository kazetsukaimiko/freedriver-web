package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenBotWorkerTest {

    private final FakeDevices devices = new FakeDevices();
    private final FakeRouter router = new FakeRouter();

    @Test
    void register_heartbeat_and_long_poll() {
        OpenBotWorker worker = worker();
        long started = System.nanoTime();

        worker.registerOnce();
        worker.heartbeatOnce();
        worker.pollOnce();

        assertEquals(List.of("register", "heartbeat", "poll"), devices.calls);
        assertEquals("luna", devices.hostname);
        assertEquals(List.of("techops", "yuni"), devices.agents);
        assertTrue(router.agents.isEmpty());
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
        assertEquals(Duration.ofMinutes(1), worker.sessionTimeout());
    }

    @Test
    void winning_claim_routes_to_the_named_agent() {
        UUID id = UUID.randomUUID();
        String prompt = "check appliance states";
        devices.queued = List.of(view(id, "techops", Channel.TEXT, prompt, AssignmentState.QUEUED));
        devices.claimResult = view(id, "techops", Channel.TEXT, prompt, AssignmentState.CLAIMED);

        worker().pollOnce();

        assertEquals(List.of("techops"), router.agents);
        assertEquals(List.of(prompt), router.prompts);
        assertEquals(2, devices.updates.size());
        assertEquals(AssignmentState.RUNNING, devices.updates.get(0).state());
        assertNull(devices.updates.get(0).grokSessionId());
        assertEquals(AssignmentState.SUCCEEDED, devices.updates.get(1).state());
        assertNull(devices.updates.get(1).grokSessionId());
        assertEquals("pumps are on", devices.updates.get(1).note());
    }

    @Test
    void speech_is_refused() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "techops", Channel.SPEECH, "listen", AssignmentState.QUEUED));
        devices.claimResult = view(id, "techops", Channel.SPEECH, "listen", AssignmentState.CLAIMED);

        worker().pollOnce();

        assertTrue(router.agents.isEmpty());
        assertEquals(1, devices.updates.size());
        assertEquals(AssignmentState.FAILED, devices.updates.get(0).state());
        assertEquals("refused SPEECH", devices.updates.get(0).note());
        assertNull(devices.updates.get(0).grokSessionId());
    }

    @Test
    void lost_claim_skips_routing() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "techops", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimError = new DeviceRejected(409);

        worker().pollOnce();

        assertTrue(router.agents.isEmpty());
        assertTrue(devices.updates.isEmpty());
    }

    @Test
    void expired_assignment_skips_routing() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "techops", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "techops", Channel.TEXT, "hello", AssignmentState.CLAIMED);
        devices.watched = () -> view(id, "techops", Channel.TEXT, "hello", AssignmentState.EXPIRED);

        worker().pollOnce();

        assertTrue(router.agents.isEmpty());
        assertTrue(devices.updates.isEmpty());
    }

    @Test
    void unknown_agent_fails_before_routing() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "dispatcher", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "dispatcher", Channel.TEXT, "hello", AssignmentState.CLAIMED);

        worker().pollOnce();

        assertTrue(router.agents.isEmpty());
        assertEquals(AssignmentState.FAILED, devices.updates.get(0).state());
        assertEquals("agent is not local", devices.updates.get(0).note());
        assertNull(devices.updates.get(0).grokSessionId());
    }

    @Test
    void router_failure_fails_the_assignment() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "techops", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "techops", Channel.TEXT, "hello", AssignmentState.CLAIMED);
        router.error = new IllegalStateException("boom");

        worker().pollOnce();

        assertEquals(List.of("techops"), router.agents);
        assertEquals(AssignmentState.RUNNING, devices.updates.get(0).state());
        assertNull(devices.updates.get(0).grokSessionId());
        assertEquals(AssignmentState.FAILED, devices.updates.get(1).state());
        assertEquals("agent routing failed", devices.updates.get(1).note());
        assertNull(devices.updates.get(1).grokSessionId());
    }

    @Test
    void failed_route_keeps_the_note() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED);
        router.result = new AgentRouter.RouteResult(false, "local timeout");

        worker().pollOnce();

        assertEquals(List.of("yuni"), router.agents);
        assertEquals(AssignmentState.FAILED, devices.updates.get(1).state());
        assertEquals("local timeout", devices.updates.get(1).note());
        assertNull(devices.updates.get(1).grokSessionId());
    }

    private OpenBotWorker worker() {
        return new OpenBotWorker(
                devices,
                router,
                new DaemonSettings(
                        "luna",
                        List.of("techops", "yuni"),
                        List.of(),
                        null,
                        Duration.ofMinutes(1),
                        Duration.ofHours(1),
                        Path.of("/tmp"),
                        WorkerMode.OPENBOT,
                        List.of("openbot-route")));
    }

    private static AssignmentView view(UUID id, String agent, Channel channel, String prompt, AssignmentState state) {
        return new AssignmentView(
                id, UUID.randomUUID(), OpenBotWorker.DEVICE_ID, agent, channel, prompt, state, null, null,
                OnBusy.supersede);
    }

    private static final class FakeRouter implements AgentRouter {
        final List<String> agents = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        AgentRouter.RouteResult result = new AgentRouter.RouteResult(true, "pumps are on");
        RuntimeException error;

        @Override
        public RouteResult route(String agentName, String prompt, Duration timeout) {
            agents.add(agentName);
            prompts.add(prompt);
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private static final class FakeDevices implements DeviceApiClient {
        final List<String> calls = new ArrayList<>();
        final List<UpdateAssignmentRequest> updates = new ArrayList<>();
        List<AssignmentView> queued = List.of();
        AssignmentView claimResult;
        RuntimeException claimError;
        Supplier<AssignmentView> watched;
        String hostname;
        List<String> agents;

        @Override
        public void register(String hostname, List<String> agents) {
            calls.add("register");
            this.hostname = hostname;
            this.agents = agents;
        }

        @Override
        public void heartbeat() {
            calls.add("heartbeat");
        }

        @Override
        public List<AssignmentView> poll() {
            calls.add("poll");
            return queued;
        }

        @Override
        public AssignmentView claim(UUID assignmentId) {
            calls.add("claim");
            if (claimError != null) {
                throw claimError;
            }
            return claimResult;
        }

        @Override
        public AssignmentView assignment(UUID assignmentId) {
            calls.add("assignment");
            if (watched != null) {
                return watched.get();
            }
            return claimResult;
        }

        @Override
        public AssignmentView update(UUID assignmentId, UpdateAssignmentRequest request) {
            calls.add("update");
            updates.add(request);
            return claimResult;
        }
    }
}
