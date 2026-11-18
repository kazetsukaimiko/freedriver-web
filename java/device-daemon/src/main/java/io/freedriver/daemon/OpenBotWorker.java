package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The dispatch connector. It takes this host's jobs from the dispatcher ({@link HostJobs}), puts
 * each prompt into the named agent's queue ({@link AgentHost}), and watches the delivery. The agent
 * posts its own result with {@code finish_job}; the connector only fails a job that was refused or
 * whose turn ended with the job still open. It has no HTTP of its own.
 */
final class OpenBotWorker implements AutoCloseable {

    static final String DEVICE_ID = "freedriver-openbot";
    static final int MAX_HELD = 64;
    static final int MAX_BOTS = 32;
    static final int MAX_NAME = 64;
    static final int TAKE_WAIT_SECONDS = 50;
    static final Duration ROSTER_EVERY = Duration.ofSeconds(30);
    static final Duration WATCH_EVERY = Duration.ofSeconds(2);
    static final Duration DISPATCHER_PAUSE = Duration.ofSeconds(2);
    static final String TURN_ENDED = "the turn ended without a result";
    static final String DELIMITER = "----- job prompt below this line -----";

    private static final Pattern DELIVERY_REF = Pattern.compile("[\\x21-\\x7E]{1,128}");
    private static final Set<String> WAITING = Set.of("queued", "starting");
    private static final Set<String> ENDED = Set.of("completed", "failed", "interrupted", "cancelled");

    private final HostJobs host;
    private final AgentHost agents;
    private final String hostname;
    private final List<String> configured;
    private final Supplier<List<String>> forbidden;
    private final ExecutorService jobs;
    private final Consumer<String> log;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private ScheduledExecutorService schedule;
    private Thread taker;

    /** One job the connector holds. Only one task works on it at a time. */
    static final class Held {
        final UUID assignmentId;
        final String agentName;
        final String prompt;
        final AtomicBoolean busy = new AtomicBoolean();
        volatile String agentId;
        volatile String deliveryId;
        volatile boolean sent;
        volatile boolean started;
        volatile String finishNote;
        volatile String loggedStatus;

        Held(UUID assignmentId, String agentName, String prompt) {
            this.assignmentId = assignmentId;
            this.agentName = agentName;
            this.prompt = prompt;
        }
    }

    OpenBotWorker(
            HostJobs host,
            AgentHost agents,
            DaemonSettings settings,
            Supplier<List<String>> forbidden,
            ExecutorService jobs,
            Consumer<String> log) {
        this.host = host;
        this.agents = agents;
        this.hostname = settings.hostname();
        this.configured = List.copyOf(settings.agents());
        this.forbidden = forbidden == null ? List::of : forbidden;
        this.jobs = jobs;
        this.log = log == null ? line -> { } : log;
    }

    static OpenBotWorker create(HostJobs host, AgentHost agents, DaemonSettings settings, Supplier<List<String>> forbidden) {
        return new OpenBotWorker(
                host, agents, settings, forbidden,
                Executors.newVirtualThreadPerTaskExecutor(),
                line -> System.err.println(line));
    }

    /** Starts the roster loop, the take loop and the watch loop. Each keeps going after a failure. */
    void start() {
        schedule = Executors.newScheduledThreadPool(2, runnable -> Thread.ofPlatform().daemon().unstarted(runnable));
        schedule.scheduleWithFixedDelay(this::reportQuietly, 0, ROSTER_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        schedule.scheduleWithFixedDelay(this::watchOnce, WATCH_EVERY.toMillis(), WATCH_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        taker = Thread.ofPlatform().name("connector-take").start(this::takeLoop);
    }

    void join() throws InterruptedException {
        if (taker != null) {
            taker.join();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (taker != null) {
            taker.interrupt();
        }
        if (schedule != null) {
            schedule.shutdownNow();
        }
        jobs.shutdownNow();
    }

    // ---- roster -------------------------------------------------------------------------------

    /**
     * Reports the configured agents that OpenBot lists, under OpenBot's spelling. Returns the
     * report, or null when none is listed and nothing was sent.
     */
    HostReport reportOnce() {
        List<AgentHost.Agent> listed = agents.agents();
        List<ReportedBot> bots = new ArrayList<>();
        for (String name : configured) {
            if (bots.size() == MAX_BOTS) {
                break;
            }
            List<AgentHost.Agent> matches = matching(listed, name);
            if (matches.size() != 1) {
                continue;
            }
            String spelled = matches.get(0).name();
            if (spelled.isBlank() || spelled.length() > MAX_NAME) {
                continue;
            }
            bots.add(new ReportedBot(spelled, busy(name) ? "busy" : "idle", ""));
        }
        if (bots.isEmpty()) {
            return null;
        }
        HostReport report = new HostReport(hostname, bots);
        host.report(report);
        return report;
    }

    private void reportQuietly() {
        try {
            reportOnce();
        } catch (RuntimeException failed) {
            log.accept("report skipped: " + reason(failed));
        }
    }

    private boolean busy(String configuredName) {
        for (Held job : held.values()) {
            if (configuredName.equalsIgnoreCase(job.agentName)) {
                return true;
            }
        }
        return false;
    }

    // ---- take ---------------------------------------------------------------------------------

    private void takeLoop() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            try {
                takeOnce(TAKE_WAIT_SECONDS);
            } catch (RuntimeException failed) {
                if (closed) {
                    return;
                }
                log.accept("take failed: " + reason(failed));
                try {
                    Thread.sleep(DISPATCHER_PAUSE);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** The ids every take names: each job the connector holds, at most 64. */
    List<UUID> holding() {
        return held.keySet().stream().limit(MAX_HELD).toList();
    }

    /** One take. New jobs are handed to their own task; the excess over 64 is failed first. */
    List<AssignmentView> takeOnce(int waitSeconds) {
        List<AssignmentView> taken = host.take(new TakeRequest(waitSeconds, holding()));
        for (AssignmentView view : taken) {
            if (view == null || view.assignmentId() == null || held.containsKey(view.assignmentId())) {
                continue;
            }
            if (held.size() >= MAX_HELD) {
                finishQuietly(view.assignmentId(), "the connector already holds " + MAX_HELD + " jobs");
                continue;
            }
            Held job = adopt(view);
            if (job != null) {
                held.put(job.assignmentId, job);
                submit(job);
            }
        }
        return taken;
    }

    private Held adopt(AssignmentView view) {
        Held job = new Held(view.assignmentId(), view.agentName(), view.prompt());
        if (view.state() == AssignmentState.RUNNING) {
            job.sent = true;
            job.started = true;
            if (view.deliveryRef() == null || view.deliveryRef().isEmpty()) {
                job.finishNote = TURN_ENDED + " (no delivery)";
            } else {
                job.deliveryId = view.deliveryRef();
            }
            return job;
        }
        if (view.state() != AssignmentState.CLAIMED) {
            return null;
        }
        if (view.channel() != Channel.TEXT) {
            job.finishNote = "refused " + (view.channel() == null ? "channel" : view.channel().name());
        } else if (view.prompt() == null || view.prompt().isBlank()) {
            job.finishNote = "missing prompt";
        }
        return job;
    }

    // ---- watch --------------------------------------------------------------------------------

    /** One pass: each held job that no task is working on gets one. */
    void watchOnce() {
        for (Held job : held.values()) {
            submit(job);
        }
    }

    private void submit(Held job) {
        if (closed || !job.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            jobs.execute(() -> {
                try {
                    step(job);
                } catch (RuntimeException failed) {
                    log.accept("job " + job.assignmentId + " waits: " + reason(failed));
                } finally {
                    job.busy.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            job.busy.set(false);
        }
    }

    /** Moves one job one step. Anything that cannot be reached leaves it held for the next pass. */
    void step(Held job) {
        if (job.finishNote != null) {
            finish(job);
            return;
        }
        if (!job.sent) {
            send(job);
            return;
        }
        watch(job);
    }

    private void send(Held job) {
        Optional<AgentHost.Agent> agent = resolve(job);
        if (agent.isEmpty()) {
            return;
        }
        String id = agent.get().id();
        AgentHost.SendResult result = agents.send(id, job.assignmentId.toString(), message(job.assignmentId, job.prompt));
        job.sent = true;
        switch (result) {
            case AgentHost.Sent sent -> {
                if (sent.deliveryIds().isEmpty()) {
                    fail(job, "OpenBot answered with no delivery");
                    return;
                }
                String deliveryId = sent.deliveryIds().get(0);
                if (!DELIVERY_REF.matcher(deliveryId).matches() || !safeSegment(deliveryId)) {
                    fail(job, "OpenBot answered with a delivery id the dispatcher cannot store");
                    return;
                }
                if (job.deliveryId != null && !job.deliveryId.equals(deliveryId)) {
                    fail(job, "OpenBot answered with a second delivery for this job");
                    return;
                }
                job.agentId = id;
                job.deliveryId = deliveryId;
            }
            case AgentHost.Refused refused -> fail(job, "OpenBot refused the message: " + refused.error());
            case AgentHost.Lost lost -> fail(job, lost.reason());
        }
    }

    private void watch(Held job) {
        if (job.agentId == null) {
            Optional<AgentHost.Agent> agent = resolve(job);
            if (agent.isEmpty()) {
                return;
            }
            job.agentId = agent.get().id();
        }
        AgentHost.Delivery delivery = agents.delivery(job.agentId, job.deliveryId);
        if (delivery.gone()) {
            fail(job, TURN_ENDED + " (gone)");
            return;
        }
        String status = delivery.status();
        if (WAITING.contains(status)) {
            return;
        }
        if ("running".equals(status)) {
            if (!job.started) {
                startOnce(job);
            }
            return;
        }
        if (ENDED.contains(status)) {
            fail(job, TURN_ENDED + " (" + status + ")");
            return;
        }
        if (!statusName(status).equals(job.loggedStatus)) {
            job.loggedStatus = statusName(status);
            log.accept("job " + job.assignmentId + " delivery status " + job.loggedStatus);
        }
    }

    private void startOnce(Held job) {
        try {
            host.start(job.assignmentId, new StartRequest(job.deliveryId));
            job.started = true;
        } catch (DeviceRejected rejected) {
            if (rejected.status / 100 != 5) {
                job.started = true;
            }
            throw rejected;
        }
    }

    /**
     * The connector's only end of a job: {@code finish} with succeeded false. The job stops being
     * held only when the answer is a finished state; that state and its note stand.
     */
    private void fail(Held job, String note) {
        job.finishNote = note;
        finish(job);
    }

    private void finish(Held job) {
        Optional<AssignmentView> answer = host.finish(job.assignmentId, new FinishRequest(false, redact(job.finishNote)));
        if (answer.isPresent() && terminal(answer.get().state())) {
            held.remove(job.assignmentId);
        }
    }

    private void finishQuietly(UUID assignmentId, String note) {
        try {
            host.finish(assignmentId, new FinishRequest(false, redact(note)));
        } catch (RuntimeException failed) {
            log.accept("job " + assignmentId + " not finished: " + reason(failed));
        }
    }

    /**
     * The one OpenBot agent this job names: a configured name equal to {@code agentName} without
     * regard to case, and exactly one listed agent with that name and a usable id. Anything else
     * fails the job. Empty when the job was failed or OpenBot cannot be reached.
     */
    private Optional<AgentHost.Agent> resolve(Held job) {
        String name = job.agentName;
        String wanted = null;
        if (name != null) {
            for (String candidate : configured) {
                if (candidate.equalsIgnoreCase(name)) {
                    wanted = candidate;
                    break;
                }
            }
        }
        if (wanted == null) {
            fail(job, "agent is not on this host");
            return Optional.empty();
        }
        List<AgentHost.Agent> matches = matching(agents.agents(), wanted);
        if (matches.size() != 1) {
            fail(job, matches.isEmpty() ? "agent is not listed by OpenBot" : "two OpenBot agents have this name");
            return Optional.empty();
        }
        if (!safeSegment(matches.get(0).id())) {
            fail(job, "the OpenBot agent id is not usable");
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private static List<AgentHost.Agent> matching(List<AgentHost.Agent> listed, String name) {
        return listed.stream().filter(agent -> agent.name().equalsIgnoreCase(name)).toList();
    }

    static boolean safeSegment(String id) {
        return id != null && !id.isEmpty() && !".".equals(id) && !"..".equals(id)
                && id.indexOf('/') < 0 && id.indexOf('\\') < 0 && id.indexOf('%') < 0;
    }

    /** The fixed instruction, a delimiter line, then the prompt unchanged. */
    static String message(UUID assignmentId, String prompt) {
        return "This message is dispatch job " + assignmentId + ". Answer it by calling the tool finish_job "
                + "of the MCP server freedriver-openbot with assignmentId " + assignmentId
                + ", succeeded true or false, and your reply as note. Call it before this turn ends: "
                + "a job whose turn ends without finish_job is failed. If part of the work is still with "
                + "a teammate, say so in the note.\n"
                + DELIMITER + "\n"
                + prompt;
    }

    private String redact(String note) {
        List<String> values = new ArrayList<>(forbidden.get());
        values.addAll(agents.forbidden());
        return Notes.forUpdate(note, values.toArray(String[]::new));
    }

    Map<UUID, Held> held() {
        return held;
    }

    private static boolean terminal(AssignmentState state) {
        return state == AssignmentState.SUCCEEDED
                || state == AssignmentState.FAILED
                || state == AssignmentState.EXPIRED;
    }

    private static String statusName(String status) {
        if (status == null) {
            return "(none)";
        }
        String cleaned = status.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "?");
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private static String reason(RuntimeException failed) {
        if (failed instanceof DeviceRejected rejected) {
            return "dispatcher answered " + rejected.status;
        }
        if (failed instanceof AgentHost.Unavailable) {
            return "OpenBot is not reachable";
        }
        return failed.getClass().getSimpleName();
    }
}
