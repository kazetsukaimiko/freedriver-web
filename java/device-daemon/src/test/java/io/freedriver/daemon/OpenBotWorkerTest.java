package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenBotWorkerTest {

    private static final String SCRIPTS_TOKEN = "openbot-scripts-token";
    private static final String CLIENT_SECRET = "client-secret-value";

    private final FakeHost host = new FakeHost();
    private final FakeAgents agents = new FakeAgents();
    private final List<String> log = new CopyOnWriteArrayList<>();

    @Test
    void happy_path_the_agent_finished_first_and_its_result_stands() {
        OpenBotWorker worker = worker(List.of("techops", "yuni"));
        UUID id = UUID.randomUUID();
        String prompt = "check appliance states\n  with  spaces ";
        host.takes.add(List.of(claimed(id, "techops", prompt)));

        worker.takeOnce(50);

        assertEquals(1, agents.sends.size());
        Send send = agents.sends.get(0);
        assertEquals("agent-techops", send.agentId);
        assertEquals(id.toString(), send.key);
        assertEquals(OpenBotWorker.message(id, prompt), send.text);
        assertTrue(send.text.endsWith("\n" + OpenBotWorker.DELIMITER + "\n" + prompt));
        assertTrue(send.text.contains("finish_job"));
        assertTrue(send.text.contains("freedriver-openbot"));
        assertTrue(send.text.contains(id.toString()));
        assertTrue(send.text.contains("before this turn ends"));

        agents.status.put("d-" + id, "queued");
        worker.watchOnce();
        agents.status.put("d-" + id, "starting");
        worker.watchOnce();
        assertTrue(host.starts.isEmpty());

        agents.status.put("d-" + id, "running");
        worker.watchOnce();
        worker.watchOnce();
        assertEquals(List.of(id + "=d-" + id), host.starts);

        host.finishAnswer = (assignment) -> Optional.of(view(assignment, "techops", AssignmentState.SUCCEEDED, null));
        agents.status.put("d-" + id, "completed");
        worker.watchOnce();

        assertEquals(1, host.finishes.size());
        assertFalse(host.finishes.get(0).request.succeeded());
        assertTrue(worker.held().isEmpty());
        worker.watchOnce();
        assertEquals(1, host.finishes.size());
        assertTrue(log.isEmpty());
        assertTrue(host.finishes.stream().noneMatch(f -> f.request.succeeded()));
    }

    @Test
    void a_turn_that_ended_with_the_job_open_is_failed() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(claimed(id, "techops", "go")));
        worker.takeOnce(50);
        for (String ended : List.of("failed")) {
            agents.status.put("d-" + id, ended);
        }
        worker.watchOnce();

        assertEquals(1, host.finishes.size());
        assertEquals(new FinishRequest(false, "the turn ended without a result (failed)"), host.finishes.get(0).request);
        assertTrue(worker.held().isEmpty());
    }

    @Test
    void each_ended_status_and_a_gone_delivery_finish_the_job() {
        for (String status : List.of("completed", "failed", "interrupted", "cancelled", "GONE")) {
            FakeHost freshHost = new FakeHost();
            FakeAgents freshAgents = new FakeAgents();
            freshAgents.listed = List.of(new AgentHost.Agent("agent-techops", "techops"));
            OpenBotWorker worker = new OpenBotWorker(freshHost, freshAgents, settings(List.of("techops")),
                    () -> List.of(CLIENT_SECRET), inline(), log::add);
            UUID id = UUID.randomUUID();
            freshHost.takes.add(List.of(claimed(id, "techops", "go")));
            worker.takeOnce(50);
            if ("GONE".equals(status)) {
                freshAgents.status.remove("d-" + id);
            } else {
                freshAgents.status.put("d-" + id, status);
            }
            worker.watchOnce();
            assertEquals(1, freshHost.finishes.size(), status);
            String expected = "GONE".equals(status) ? "gone" : status;
            assertEquals("the turn ended without a result (" + expected + ")", freshHost.finishes.get(0).request.note());
        }
    }

    @Test
    void finish_409_keeps_the_job_and_calls_finish_again() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(claimed(id, "techops", "go")));
        worker.takeOnce(50);
        agents.status.put("d-" + id, "completed");
        host.finishAnswer = assignment -> Optional.empty();

        worker.watchOnce();
        assertEquals(1, host.finishes.size());
        assertTrue(worker.held().containsKey(id));

        host.finishAnswer = assignment -> Optional.of(view(assignment, "techops", AssignmentState.FAILED, "x"));
        worker.watchOnce();
        assertEquals(2, host.finishes.size());
        assertTrue(worker.held().isEmpty());
        assertTrue(log.isEmpty());
    }

    @Test
    void start_409_is_not_an_end_and_start_is_not_called_again() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(claimed(id, "techops", "go")));
        worker.takeOnce(50);
        agents.status.put("d-" + id, "running");
        host.startAnswer = Optional.empty();

        worker.watchOnce();
        worker.watchOnce();
        worker.watchOnce();

        assertEquals(1, host.starts.size());
        assertEquals(1, agents.sends.size());
        assertTrue(worker.held().containsKey(id));
        assertTrue(host.finishes.isEmpty());
    }

    @Test
    void each_refusal_finishes_the_job_as_failed_with_the_reason() {
        record Case(AssignmentView view, Function<UUID, AgentHost.SendResult> result, List<AgentHost.Agent> listed, String note) {
        }
        UUID any = UUID.randomUUID();
        List<AgentHost.Agent> normal = List.of(new AgentHost.Agent("agent-techops", "techops"));
        List<Case> cases = List.of(
                new Case(view(any, "techops", Channel.SPEECH, "listen", AssignmentState.CLAIMED, null), null, normal, "refused SPEECH"),
                new Case(view(any, "techops", Channel.TEXT, "  ", AssignmentState.CLAIMED, null), null, normal, "missing prompt"),
                new Case(claimed(any, "stranger", "go"), null, normal, "agent is not on this host"),
                new Case(claimed(any, "techops", "go"), null, List.of(), "agent is not listed by OpenBot"),
                new Case(claimed(any, "techops", "go"), null,
                        List.of(new AgentHost.Agent("a", "TechOps"), new AgentHost.Agent("b", "techops")),
                        "two OpenBot agents have this name"),
                new Case(claimed(any, "techops", "go"), null, List.of(new AgentHost.Agent("..", "techops")),
                        "the OpenBot agent id is not usable"),
                new Case(claimed(any, "techops", "go"), null, List.of(new AgentHost.Agent("a/b", "techops")),
                        "the OpenBot agent id is not usable"),
                new Case(claimed(any, "techops", "go"), null, List.of(new AgentHost.Agent("a%2f", "techops")),
                        "the OpenBot agent id is not usable"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Refused(403, "Local scripts are off for this agent"),
                        normal, "OpenBot refused the message: Local scripts are off for this agent"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Refused(429, "hourly limit"),
                        normal, "OpenBot refused the message: hourly limit"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Refused(413, "too large " + SCRIPTS_TOKEN),
                        normal, "OpenBot refused the message: too large [redacted]"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Sent(List.of()),
                        normal, "OpenBot answered with no delivery"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Sent(List.of("has space")),
                        normal, "OpenBot answered with a delivery id the dispatcher cannot store"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Sent(List.of("x".repeat(129))),
                        normal, "OpenBot answered with a delivery id the dispatcher cannot store"),
                new Case(claimed(any, "techops", "go"), id -> new AgentHost.Lost("the message call ended without a receipt"),
                        normal, "the message call ended without a receipt"));
        for (Case c : cases) {
            FakeHost freshHost = new FakeHost();
            FakeAgents freshAgents = new FakeAgents();
            freshAgents.listed = c.listed;
            if (c.result != null) {
                freshAgents.sendResult = c.result;
            }
            OpenBotWorker worker = new OpenBotWorker(freshHost, freshAgents, settings(List.of("techops")),
                    () -> List.of(CLIENT_SECRET), inline(), log::add);
            freshHost.takes.add(List.of(c.view));
            worker.takeOnce(50);

            assertEquals(1, freshHost.finishes.size(), c.note);
            assertEquals(new FinishRequest(false, c.note), freshHost.finishes.get(0).request, c.note);
            assertTrue(freshHost.starts.isEmpty(), c.note);
            assertTrue(worker.held().isEmpty(), c.note);
            if (c.result == null) {
                assertTrue(freshAgents.sends.isEmpty(), c.note);
            } else {
                assertEquals(1, freshAgents.sends.size(), c.note);
            }
        }
    }

    @Test
    void a_failure_note_is_redacted_before_it_is_cut() {
        OpenBotWorker worker = worker(List.of("techops"));
        String error = "x".repeat(1990) + SCRIPTS_TOKEN + CLIENT_SECRET;
        agents.sendResult = id -> new AgentHost.Refused(400, error);
        host.takes.add(List.of(claimed(UUID.randomUUID(), "techops", "go")));
        worker.takeOnce(50);

        String note = host.finishes.get(0).request.note();
        assertEquals(Notes.LIMIT, note.length());
        assertFalse(note.contains("openbot-scr"));
        assertFalse(note.contains("client-se"));
    }

    @Test
    void two_agents_with_jobs_at_once_run_side_by_side() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        agents.listed = List.of(new AgentHost.Agent("agent-a", "alpha"), new AgentHost.Agent("agent-b", "beta"));
        agents.beforeSend = agentId -> {
            if ("agent-a".equals(agentId)) {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        OpenBotWorker worker = new OpenBotWorker(host, agents, settings(List.of("alpha", "beta")),
                () -> List.of(), pool, log::add);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        host.takes.add(List.of(claimed(a, "alpha", "slow"), claimed(b, "beta", "fast")));

        worker.takeOnce(50);
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        waitFor(() -> agents.sends.stream().anyMatch(s -> s.agentId.equals("agent-b")));
        agents.status.put("d-" + b, "running");
        worker.watchOnce();
        waitFor(() -> host.starts.contains(b + "=d-" + b));
        assertTrue(agents.sends.stream().noneMatch(s -> s.agentId.equals("agent-a")));

        release.countDown();
        waitFor(() -> agents.sends.size() == 2);
        pool.shutdownNow();
    }

    @Test
    void a_job_handed_back_running_is_watched_not_sent_again() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(view(id, "techops", Channel.TEXT, "go", AssignmentState.RUNNING, "d-old")));
        agents.status.put("d-old", "running");

        worker.takeOnce(50);
        worker.watchOnce();

        assertTrue(agents.sends.isEmpty());
        assertTrue(host.starts.isEmpty());
        assertEquals(List.of("agent-techops/d-old", "agent-techops/d-old"), agents.reads);

        agents.status.put("d-old", "interrupted");
        worker.watchOnce();
        assertEquals("the turn ended without a result (interrupted)", host.finishes.get(0).request.note());
    }

    @Test
    void openbot_away_and_back_during_a_watch_keeps_the_job() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(claimed(id, "techops", "go")));
        worker.takeOnce(50);

        agents.away = true;
        worker.watchOnce();
        worker.watchOnce();
        assertTrue(worker.held().containsKey(id));
        assertTrue(host.finishes.isEmpty());
        assertTrue(log.stream().allMatch(line -> line.endsWith("OpenBot is not reachable")));

        agents.away = false;
        agents.status.put("d-" + id, "running");
        worker.watchOnce();
        assertEquals(1, host.starts.size());
        assertEquals(1, agents.sends.size());
    }

    @Test
    void openbot_away_before_the_send_keeps_the_job_until_it_is_back() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        agents.away = true;
        host.takes.add(List.of(claimed(id, "techops", "go")));
        worker.takeOnce(50);
        worker.watchOnce();
        assertTrue(agents.sends.isEmpty());
        assertTrue(host.finishes.isEmpty());

        agents.away = false;
        worker.watchOnce();
        assertEquals(1, agents.sends.size());
    }

    @Test
    void an_unknown_delivery_status_keeps_the_job_and_is_logged_once() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(claimed(id, "techops", "go")));
        worker.takeOnce(50);
        agents.status.put("d-" + id, "paused");

        worker.watchOnce();
        worker.watchOnce();
        worker.watchOnce();

        assertTrue(worker.held().containsKey(id));
        assertTrue(host.finishes.isEmpty());
        assertEquals(List.of("job " + id + " delivery status paused"), log);
    }

    @Test
    void every_take_names_every_held_job() {
        OpenBotWorker worker = worker(List.of("techops"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        host.takes.add(List.of(claimed(first, "techops", "one")));
        host.takes.add(List.of(claimed(second, "techops", "two")));
        host.takes.add(List.of());

        worker.takeOnce(50);
        worker.takeOnce(50);
        worker.takeOnce(50);

        assertEquals(List.of(), host.holdings.get(0));
        assertEquals(List.of(first), host.holdings.get(1));
        assertEquals(List.of(first, second).stream().sorted().toList(), host.holdings.get(2).stream().sorted().toList());
        assertEquals(List.of(50, 50, 50), host.waits);
    }

    @Test
    void the_excess_over_64_is_failed_before_it_joins_the_open_set() {
        OpenBotWorker worker = worker(List.of("techops"));
        agents.status.clear();
        List<AssignmentView> first = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            first.add(claimed(UUID.randomUUID(), "techops", "job " + i));
        }
        host.takes.add(first);
        worker.takeOnce(50);
        assertEquals(60, worker.held().size());

        List<AssignmentView> second = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            second.add(claimed(UUID.randomUUID(), "techops", "more " + i));
        }
        host.takes.add(second);
        host.takes.add(List.of());
        worker.takeOnce(50);

        assertEquals(64, worker.held().size());
        assertEquals(6, host.finishes.size());
        for (Finish finish : host.finishes) {
            assertEquals(new FinishRequest(false, "the connector already holds 64 jobs"), finish.request);
            assertFalse(worker.held().containsKey(finish.assignmentId));
        }
        assertEquals(64, agents.sends.size());
        worker.takeOnce(50);
        assertEquals(64, host.holdings.get(2).size());
        assertTrue(host.holdings.stream().allMatch(holding -> holding.size() <= 64));
    }

    @Test
    void roster_reports_listed_configured_agents_under_openbot_spelling() {
        agents.listed = List.of(
                new AgentHost.Agent("agent-1", "tech ops"),
                new AgentHost.Agent("agent-2", "Yuni"),
                new AgentHost.Agent("agent-3", "YUNI"),
                new AgentHost.Agent("agent-4", "Other"),
                new AgentHost.Agent("agent-5", "Kaze"));
        OpenBotWorker worker = worker(List.of("Tech Ops", "yuni", "kaze", "missing"));
        UUID id = UUID.randomUUID();
        host.takes.add(List.of(claimed(id, "Tech Ops", "go")));
        worker.takeOnce(50);

        HostReport report = worker.reportOnce();

        assertEquals("luna", report.hostname());
        assertEquals(List.of(new ReportedBot("tech ops", "busy", ""), new ReportedBot("Kaze", "idle", "")), report.bots());
        assertEquals(List.of(report), host.reports);
        assertEquals("agent-1", agents.sends.get(0).agentId);
    }

    @Test
    void no_report_is_sent_when_no_configured_agent_is_listed() {
        agents.listed = List.of(new AgentHost.Agent("agent-4", "Other"));
        assertNull(worker(List.of("techops")).reportOnce());
        assertTrue(host.reports.isEmpty());
    }

    @Test
    void a_report_holds_at_most_32_bots_and_activity_stays_empty() {
        List<String> names = new ArrayList<>();
        List<AgentHost.Agent> listed = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            names.add("agent " + i);
            listed.add(new AgentHost.Agent("id-" + i, "Agent " + i));
        }
        agents.listed = listed;
        HostReport report = worker(names).reportOnce();
        assertEquals(32, report.bots().size());
        assertTrue(report.bots().stream().allMatch(bot -> "".equals(bot.activity())));
    }

    @Test
    void no_log_line_holds_a_prompt_a_note_or_a_secret() {
        OpenBotWorker worker = worker(List.of("techops"));
        String prompt = "prompt-" + CLIENT_SECRET;
        host.takes.add(List.of(claimed(UUID.randomUUID(), "techops", prompt)));
        host.finishFails = true;
        agents.sendResult = id -> new AgentHost.Refused(400, "error " + SCRIPTS_TOKEN);
        worker.takeOnce(50);
        worker.watchOnce();
        assertFalse(log.isEmpty());
        for (String line : log) {
            assertFalse(line.contains(prompt));
            assertFalse(line.contains(SCRIPTS_TOKEN));
            assertFalse(line.contains(CLIENT_SECRET));
            assertFalse(line.contains("error"));
        }
    }

    private OpenBotWorker worker(List<String> configured) {
        if (agents.listed == null) {
            List<AgentHost.Agent> listed = new ArrayList<>();
            for (String name : configured) {
                listed.add(new AgentHost.Agent("agent-" + name.replace(' ', '-'), name));
            }
            agents.listed = listed;
        }
        return new OpenBotWorker(host, agents, settings(configured), () -> List.of(CLIENT_SECRET), inline(), log::add);
    }

    private static DaemonSettings settings(List<String> configured) {
        return DaemonSettings.openBot("luna", configured, Path.of("/nonexistent/automation"));
    }

    static AssignmentView claimed(UUID id, String agent, String prompt) {
        return view(id, agent, Channel.TEXT, prompt, AssignmentState.CLAIMED, null);
    }

    static AssignmentView view(UUID id, String agent, Channel channel, String prompt, AssignmentState state, String deliveryRef) {
        return new AssignmentView(id, UUID.randomUUID(), OpenBotWorker.DEVICE_ID, agent, channel, prompt, state, null, null, null, deliveryRef);
    }

    private static AssignmentView view(UUID id, String agent, AssignmentState state, String note) {
        return new AssignmentView(id, UUID.randomUUID(), OpenBotWorker.DEVICE_ID, agent, Channel.TEXT, null, state, null, note, null, null);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met");
            }
            Thread.sleep(10);
        }
    }

    static ExecutorService inline() {
        return new AbstractExecutorService() {
            private volatile boolean shut;

            @Override
            public void shutdown() {
                shut = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shut = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shut;
            }

            @Override
            public boolean isTerminated() {
                return shut;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

    record Send(String agentId, String key, String text) {
    }

    record Finish(UUID assignmentId, FinishRequest request) {
    }

    static final class FakeHost implements HostJobs {
        final Deque<List<AssignmentView>> takes = new ArrayDeque<>();
        final List<List<UUID>> holdings = new CopyOnWriteArrayList<>();
        final List<Integer> waits = new CopyOnWriteArrayList<>();
        final List<HostReport> reports = new CopyOnWriteArrayList<>();
        final List<String> starts = new CopyOnWriteArrayList<>();
        final List<Finish> finishes = new CopyOnWriteArrayList<>();
        volatile Optional<AssignmentView> startAnswer;
        volatile Function<UUID, Optional<AssignmentView>> finishAnswer =
                id -> Optional.of(view(id, "techops", AssignmentState.FAILED, "failed"));
        volatile boolean finishFails;

        @Override
        public DeviceView report(HostReport report) {
            reports.add(report);
            return new DeviceView(OpenBotWorker.DEVICE_ID, report.hostname(), List.of(), null, Map.of());
        }

        @Override
        public synchronized List<AssignmentView> take(TakeRequest request) {
            holdings.add(request.holding() == null ? List.of() : List.copyOf(request.holding()));
            waits.add(request.waitSeconds());
            List<AssignmentView> next = takes.poll();
            return next == null ? List.of() : next;
        }

        @Override
        public Optional<AssignmentView> start(UUID assignmentId, StartRequest request) {
            starts.add(assignmentId + "=" + request.deliveryRef());
            if (startAnswer != null) {
                return startAnswer;
            }
            return Optional.of(view(assignmentId, "techops", Channel.TEXT, null, AssignmentState.RUNNING, request.deliveryRef()));
        }

        @Override
        public Optional<AssignmentView> finish(UUID assignmentId, FinishRequest request) {
            finishes.add(new Finish(assignmentId, request));
            if (finishFails) {
                throw new IllegalStateException("device call failed");
            }
            return finishAnswer.apply(assignmentId);
        }
    }

    static final class FakeAgents implements AgentHost {
        volatile List<Agent> listed;
        final List<Send> sends = new CopyOnWriteArrayList<>();
        final List<String> reads = new CopyOnWriteArrayList<>();
        final Map<String, String> status = Collections.synchronizedMap(new HashMap<>());
        volatile Function<UUID, SendResult> sendResult;
        volatile java.util.function.Consumer<String> beforeSend = id -> { };
        volatile boolean away;

        @Override
        public List<Agent> agents() {
            if (away) {
                throw new Unavailable("away");
            }
            return listed;
        }

        @Override
        public SendResult send(String agentId, String clientMessageId, String text) {
            if (away) {
                throw new Unavailable("away");
            }
            beforeSend.accept(agentId);
            sends.add(new Send(agentId, clientMessageId, text));
            UUID id = UUID.fromString(clientMessageId);
            if (sendResult != null) {
                return sendResult.apply(id);
            }
            status.putIfAbsent("d-" + id, "queued");
            return new Sent(List.of("d-" + id));
        }

        @Override
        public Delivery delivery(String agentId, String deliveryId) {
            if (away) {
                throw new Unavailable("away");
            }
            reads.add(agentId + "/" + deliveryId);
            String value = status.get(deliveryId);
            return value == null ? Delivery.missing() : Delivery.of(value);
        }

        @Override
        public List<String> forbidden() {
            return List.of(SCRIPTS_TOKEN);
        }
    }
}
