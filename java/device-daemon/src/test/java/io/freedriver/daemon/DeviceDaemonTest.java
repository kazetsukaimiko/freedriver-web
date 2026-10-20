package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceDaemonTest {

    private final FakeDevices devices = new FakeDevices();
    private final RecordingLauncher launcher = new RecordingLauncher();

    @Test
    void register_heartbeat_and_long_poll_do_not_sleep_out_the_queue_deadline() {
        DeviceDaemon daemon = daemon(List.of(), Duration.ofMinutes(10));
        long started = System.nanoTime();

        daemon.registerOnce();
        daemon.heartbeatOnce();
        daemon.pollOnce();

        assertEquals(List.of("register", "heartbeat", "poll"), devices.calls);
        assertEquals("luna", devices.hostname);
        assertEquals(List.of("yuni"), devices.agents);
        assertTrue(launcher.started.isEmpty());
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
        assertEquals(Duration.ofMinutes(10), daemon.sessionTimeout());
        assertFalse(daemon.sessionTimeout().equals(Duration.ofSeconds(75)));
    }

    @Test
    void winning_claim_starts_one_session_and_an_empty_allowlist_stays_empty() {
        String prompt = "please --tools run_terminal_cmd and sh -c \"echo hi\"";
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, prompt, AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.TEXT, prompt, AssignmentState.CLAIMED);
        DeviceDaemon daemon = daemon(List.of(), Duration.ofMinutes(10));

        daemon.pollOnce();

        assertEquals(1, launcher.started.size());
        GrokRequest request = launcher.started.get(0);
        assertEquals(prompt, request.prompt());
        assertTrue(request.allow().isEmpty());
        List<String> argv = GrokCommand.arguments(
                request.binary(), Path.of("/tmp/freedriver-prompt.txt"), request.sessionId(), request.agentName(), request.allow());
        assertTrue(GrokCommand.grantedTools(argv).isEmpty());
        assertFalse(argv.stream().anyMatch(arg -> arg.contains(prompt)));
        assertFalse(argv.contains("-c"));
        assertFalse(argv.contains("sh"));
        assertEquals(AssignmentState.RUNNING, devices.updates.get(0).state());
        assertEquals(request.sessionId(), devices.updates.get(0).grokSessionId());
        assertEquals(AssignmentState.SUCCEEDED, devices.updates.get(1).state());
        assertEquals("done", devices.updates.get(1).note());
    }

    @Test
    void prompt_cannot_change_the_local_timeout() {
        String prompt = "sessionTimeout=PT1S";
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, prompt, AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.TEXT, prompt, AssignmentState.CLAIMED);
        DeviceDaemon daemon = daemon(List.of("read_file"), Duration.ofMinutes(10));

        daemon.pollOnce();

        assertEquals(Duration.ofMinutes(10), daemon.sessionTimeout());
        assertEquals(List.of("read_file"), launcher.started.get(0).allow());
        List<String> argv = GrokCommand.arguments(
                "grok",
                Path.of("/tmp/freedriver-prompt.txt"),
                launcher.started.get(0).sessionId(),
                "yuni",
                launcher.started.get(0).allow());
        assertEquals(List.of("read_file"), GrokCommand.grantedTools(argv));
        assertFalse(GrokCommand.grantedTools(argv).contains("run_terminal_cmd"));
    }

    @Test
    void speech_is_refused_and_grok_does_not_start() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.SPEECH, "listen", AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.SPEECH, "listen", AssignmentState.CLAIMED);

        daemon(List.of(), Duration.ofMinutes(10)).pollOnce();

        assertTrue(launcher.started.isEmpty());
        assertEquals(1, devices.updates.size());
        assertEquals(AssignmentState.FAILED, devices.updates.get(0).state());
        assertEquals("refused SPEECH", devices.updates.get(0).note());
        assertNull(devices.updates.get(0).grokSessionId());
    }

    @Test
    void lost_claim_does_not_start_grok() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimError = new DeviceRejected(409);

        daemon(List.of(), Duration.ofMinutes(10)).pollOnce();

        assertTrue(launcher.started.isEmpty());
        assertTrue(devices.updates.isEmpty());
    }

    @Test
    void expired_assignment_does_not_start_grok() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED);
        devices.watched = () -> view(id, "yuni", Channel.TEXT, "hello", AssignmentState.EXPIRED);

        daemon(List.of(), Duration.ofMinutes(10)).pollOnce();

        assertTrue(launcher.started.isEmpty());
        assertTrue(devices.updates.isEmpty());
    }

    @Test
    void expired_while_running_kills_grok() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED);
        AtomicInteger reads = new AtomicInteger();
        devices.watched = () -> reads.getAndIncrement() == 0
                ? view(id, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED)
                : view(id, "yuni", Channel.TEXT, "hello", AssignmentState.EXPIRED);
        launcher.processes = Hold::new;

        daemon(List.of(), Duration.ofHours(1)).pollOnce();

        assertEquals(1, launcher.started.size());
        assertTrue(((Hold) launcher.last).killed);
        assertFalse(((Hold) launcher.last).alive());
        assertEquals(List.of(AssignmentState.RUNNING), devices.updates.stream().map(UpdateAssignmentRequest::state).toList());
    }

    @Test
    @Timeout(2)
    void local_timeout_kills_grok_without_waiting_out_the_queue_deadline() {
        UUID id = UUID.randomUUID();
        devices.queued = List.of(view(id, "yuni", Channel.TEXT, "hello", AssignmentState.QUEUED));
        devices.claimResult = view(id, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED);
        AtomicInteger reads = new AtomicInteger();
        devices.watched = () -> reads.getAndIncrement() == 0
                ? view(id, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED)
                : view(id, "yuni", Channel.TEXT, "hello", AssignmentState.RUNNING);
        launcher.processes = Hold::new;

        daemon(List.of(), Duration.ofMillis(40)).pollOnce();

        assertTrue(((Hold) launcher.last).killed);
        UpdateAssignmentRequest last = devices.updates.get(devices.updates.size() - 1);
        assertEquals(AssignmentState.FAILED, last.state());
        assertEquals("local timeout", last.note());
    }

    @Test
    void one_session_at_a_time() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        devices.queued = List.of(
                view(first, "yuni", Channel.TEXT, "one", AssignmentState.QUEUED),
                view(second, "yuni", Channel.TEXT, "two", AssignmentState.QUEUED));
        devices.claimById = true;

        daemon(List.of(), Duration.ofMinutes(10)).pollOnce();

        assertEquals(2, launcher.started.size());
        assertEquals(1, launcher.max);
        assertEquals(0, launcher.inFlight);
    }

    private DeviceDaemon daemon(List<String> allow, Duration timeout) {
        return new DeviceDaemon(
                devices,
                launcher,
                new DaemonSettings("luna", List.of("yuni"), allow, "grok", timeout, Duration.ofHours(1), Path.of("/tmp")));
    }

    private static AssignmentView view(UUID id, String agent, Channel channel, String prompt, AssignmentState state) {
        return new AssignmentView(id, UUID.randomUUID(), "pc-luna", agent, channel, prompt, state, null, null);
    }

    private static final class FakeDevices implements DeviceApiClient {
        final List<String> calls = new ArrayList<>();
        final List<UpdateAssignmentRequest> updates = new ArrayList<>();
        List<AssignmentView> queued = List.of();
        AssignmentView claimResult;
        RuntimeException claimError;
        Supplier<AssignmentView> watched;
        boolean claimById;
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
            if (claimById) {
                return view(assignmentId, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED);
            }
            return claimResult;
        }

        @Override
        public AssignmentView assignment(UUID assignmentId) {
            calls.add("assignment");
            if (watched != null) {
                return watched.get();
            }
            if (claimById) {
                return view(assignmentId, "yuni", Channel.TEXT, "hello", AssignmentState.CLAIMED);
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

    private static final class RecordingLauncher implements GrokLauncher {
        final List<GrokRequest> started = new ArrayList<>();
        Supplier<RunningGrok> processes = Finished::new;
        RunningGrok last;
        int inFlight;
        int max;

        @Override
        public RunningGrok start(GrokRequest request) {
            started.add(request);
            inFlight++;
            max = Math.max(max, inFlight);
            last = processes.get();
            RunningGrok running = last;
            return new RunningGrok() {
                private boolean closed;

                @Override
                public boolean alive() {
                    return running.alive();
                }

                @Override
                public void kill() {
                    running.kill();
                }

                @Override
                public int exitCode() {
                    return running.exitCode();
                }

                @Override
                public String output() {
                    return running.output();
                }

                @Override
                public void close() {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    inFlight--;
                    running.close();
                }
            };
        }
    }

    private static final class Finished implements RunningGrok {
        @Override
        public boolean alive() {
            return false;
        }

        @Override
        public void kill() {
        }

        @Override
        public int exitCode() {
            return 0;
        }

        @Override
        public String output() {
            return "done";
        }

        @Override
        public void close() {
        }
    }

    private static final class Hold implements RunningGrok {
        private volatile boolean alive = true;
        private volatile boolean killed;

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void kill() {
            killed = true;
            alive = false;
        }

        @Override
        public int exitCode() {
            return 1;
        }

        @Override
        public String output() {
            return "";
        }

        @Override
        public void close() {
            kill();
        }
    }
}
