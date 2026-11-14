package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AgentRosterEntry;
import io.freedriver.dispatcher.model.AssignmentPage;
import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobPage;
import io.freedriver.dispatcher.model.JobSummary;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The dispatch queue on {@link AssignmentRepository}, {@link DeviceRepository}, and
 * {@link SubmitLocks}. Every state change is one conditional write, so replicas need no lock.
 * A wait re-reads Mongo on an interval that starts at 250 ms and rises to 1 s.
 */
@ApplicationScoped
public class JobStoreService implements JobStore {

    static final Duration LOCK_TTL = Duration.ofMinutes(3);
    private static final long FIRST_WAIT_MILLIS = 250;
    private static final long LONGEST_WAIT_MILLIS = 1000;

    private final AssignmentRepository assignments;
    private final DeviceRepository devices;
    private final SubmitLocks locks;
    private final Clock clock;
    private final JobPing jobPing;

    @Inject
    JobStoreService(AssignmentRepository assignments, DeviceRepository devices, SubmitLocks locks, JobPing jobPing) {
        this(assignments, devices, locks, Clock.systemUTC(), jobPing);
    }

    JobStoreService(AssignmentRepository assignments, DeviceRepository devices, SubmitLocks locks, Clock clock,
                    JobPing jobPing) {
        this.assignments = assignments;
        this.devices = devices;
        this.locks = locks;
        this.clock = clock;
        this.jobPing = jobPing == null ? JobPing.NONE : jobPing;
    }

    @Override
    public DeviceView register(String deviceId, String hostname, List<String> agents) {
        if (new HashSet<>(agents).size() != agents.size()) {
            throw badRequest("duplicate agent");
        }
        Map<String, String> none = Map.of();
        return viewDevice(devices.report(deviceId, hostname, List.copyOf(agents), none, none, clock.instant()));
    }

    /** Host publish. Agent order is the list order. State is idle or busy. Activity may be empty. */
    @Override
    public DeviceView report(String deviceId, String hostname, List<ReportedBot> bots) {
        Map<String, String> states = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (ReportedBot bot : bots) {
            if (states.putIfAbsent(bot.name(), bot.state().toLowerCase()) != null) {
                throw badRequest("duplicate agent");
            }
            notes.put(bot.name(), bot.activity() == null ? "" : bot.activity());
        }
        return viewDevice(devices.report(deviceId, hostname, List.copyOf(states.keySet()), states, notes,
                clock.instant()));
    }

    @Override
    public List<AgentRosterEntry> listAgents() {
        List<AgentRosterEntry> rows = new ArrayList<>();
        for (Device device : devices.listAll()) {
            Map<String, String> activity = orEmpty(device.activity);
            Map<String, String> notes = orEmpty(device.activityText);
            for (String agent : orEmpty(device.agents)) {
                String state = "busy".equals(activity.get(agent)) ? "BUSY" : "IDLE";
                rows.add(new AgentRosterEntry("bot", agent, device.hostname, state, notes.getOrDefault(agent, "")));
            }
        }
        return List.copyOf(rows);
    }

    /** The one device whose hostname and agent match. */
    @Override
    public String deviceIdFor(String server, String agentName) {
        List<Device> found = devices.find(server, agentName);
        if (found.size() > 1) {
            throw badRequest("server matches more than one host");
        }
        if (found.isEmpty()) {
            throw badRequest("unknown agent " + agentName + " on " + server);
        }
        return found.getFirst().id;
    }

    /** One step from any open state to a terminal result, so a poller can post the reply without a claim. */
    @Override
    public AssignmentView finish(String deviceId, UUID assignmentId, boolean succeeded, String note) {
        AssignmentState to = succeeded ? AssignmentState.SUCCEEDED : AssignmentState.FAILED;
        String kept = note == null || note.isBlank() ? null : note;
        return move(deviceId, assignmentId, AssignmentRepository.OPEN, to, kept, null);
    }

    @Override
    public DeviceView heartbeat(String deviceId) {
        Device device = devices.heartbeat(deviceId, clock.instant());
        if (device == null) {
            throw notFound("device");
        }
        return viewDevice(device);
    }

    @Override
    public List<DeviceView> listDevices() {
        return devices.listAll().stream().map(JobStoreService::viewDevice).toList();
    }

    @Override
    public JobView submit(String dispatcherSubject, SubmitJobRequest request, Duration wait) {
        long deadline = System.nanoTime() + (wait == null ? Duration.ZERO : wait).toNanos();
        Queued queued = enqueue(dispatcherSubject, request, true);
        try {
            notifyHosts(queued.hosts());
            if (!waitUntil(deadline, () -> allTerminal(queued.jobId()))) {
                expireOpen(queued.jobId());
            }
            return viewJob(queued.jobId(), dispatcherSubject);
        } finally {
            locks.release(dispatcherSubject);
        }
    }

    /**
     * Write the job, return its id, and nudge the OpenBot webhook on another thread. The caller
     * does not wait for that POST. A second send is another row.
     */
    @Override
    public JobView submitNow(String dispatcherSubject, SubmitJobRequest request) {
        Queued queued = enqueue(dispatcherSubject, request, false);
        Thread.ofVirtual().name("openbot-job-ping").start(() -> notifyHosts(queued.hosts()));
        return viewJob(queued.jobId(), dispatcherSubject);
    }

    @Override
    public JobView awaitTurn(String dispatcherSubject, UUID jobId, Duration delay) {
        if (jobId == null) {
            throw badRequest("jobId is required");
        }
        if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofMinutes(2)) > 0) {
            throw badRequest("delay");
        }
        String id = jobId.toString();
        viewJob(id, dispatcherSubject);
        waitUntil(System.nanoTime() + delay.toNanos(), () -> allTerminal(id));
        return viewJob(id, dispatcherSubject);
    }

    private Queued enqueue(String dispatcherSubject, SubmitJobRequest request, boolean lock) {
        List<String> deviceIds = request.deviceIds();
        if (new HashSet<>(deviceIds).size() != deviceIds.size()) {
            throw badRequest("duplicate device");
        }
        List<String> hosts = new ArrayList<>();
        for (String deviceId : deviceIds) {
            Device device = devices.findById(deviceId);
            if (device == null) {
                throw badRequest("unknown device " + deviceId);
            }
            if (!orEmpty(device.agents).contains(request.agentName())) {
                throw badRequest("unknown agent " + request.agentName() + " on " + deviceId);
            }
            if (!hosts.contains(device.hostname)) {
                hosts.add(device.hostname);
            }
        }
        Instant now = clock.instant();
        if (lock && !locks.acquire(dispatcherSubject, now, LOCK_TTL)) {
            throw conflict("submit already in flight");
        }
        String jobId = UUID.randomUUID().toString();
        List<String> written = new ArrayList<>();
        try {
            int ordinal = 0;
            for (String deviceId : deviceIds) {
                Assignment row = new Assignment();
                row.id = UUID.randomUUID().toString();
                row.jobId = jobId;
                row.deviceId = deviceId;
                row.agentName = request.agentName();
                row.dispatcherSubject = dispatcherSubject;
                row.prompt = request.prompt();
                row.channel = request.channel();
                row.onBusy = request.onBusy() == null ? OnBusy.supersede : request.onBusy();
                row.status = AssignmentState.QUEUED;
                row.ordinal = ordinal++;
                row.createdAt = now;
                row.updatedAt = now;
                assignments.persist(row);
                written.add(row.id);
            }
            return new Queued(jobId, hosts);
        } catch (RuntimeException failure) {
            written.forEach(assignments::deleteById);
            if (lock) {
                locks.release(dispatcherSubject);
            }
            throw failure;
        }
    }

    private void notifyHosts(List<String> hosts) {
        for (String hostname : hosts) {
            try {
                jobPing.queued(hostname);
            } catch (RuntimeException failure) {
                // A notifier bug must not drop a job that is already queued.
            }
        }
    }

    private record Queued(String jobId, List<String> hosts) {
    }

    @Override
    public JobView getJob(String dispatcherSubject, UUID jobId) {
        return viewJob(jobId.toString(), dispatcherSubject);
    }

    @Override
    public List<JobView> listJobs(String dispatcherSubject) {
        Map<String, List<Assignment>> byJob = new LinkedHashMap<>();
        for (Assignment row : assignments.forSubject(dispatcherSubject)) {
            if (row.jobId != null) {
                byJob.computeIfAbsent(row.jobId, id -> new ArrayList<>()).add(row);
            }
        }
        List<JobView> views = new ArrayList<>();
        for (Map.Entry<String, List<Assignment>> job : byJob.entrySet()) {
            List<Assignment> rows = job.getValue();
            rows.sort((a, b) -> Integer.compare(a.ordinal, b.ordinal));
            views.add(viewJob(job.getKey(), dispatcherSubject, rows));
        }
        return List.copyOf(views);
    }

    @Override
    public JobPage listJobPage(String dispatcherSubject, String agentName, String state, int limit, String cursor) {
        if (state != null && !state.equals("queued") && !state.equals("in-progress") && !state.equals("done")) {
            throw badRequest("state");
        }
        PageCursor after = cursor == null || cursor.isBlank() ? null : pageCursor(cursor);
        List<Document> rows = assignments.page(dispatcherSubject, agentName, state, limit + 1,
                after == null ? null : Instant.ofEpochMilli(after.millis()), after == null ? null : after.jobId());
        List<JobSummary> summaries = new ArrayList<>();
        for (Document row : rows.subList(0, Math.min(limit, rows.size()))) {
            summaries.add(summarize(row));
        }
        String next = rows.size() > limit ? pageCursor(summaries.getLast()) : null;
        return new JobPage(List.copyOf(summaries), next);
    }

    private static JobSummary summarize(Document row) {
        String deviceId = row.getString("deviceId");
        List<Document> device = row.getList("device", Document.class, List.of());
        String hostname = device.isEmpty() ? null : device.getFirst().getString("hostname");
        Date submitted = row.getDate("submittedAt");
        return new JobSummary(
                UUID.fromString(row.getString("_id")),
                row.getString("agentName"),
                hostname == null ? deviceId : hostname,
                row.getString("state"),
                submitted == null ? Instant.EPOCH : submitted.toInstant(),
                shortNote(row.getList("notes", Document.class, List.of())));
    }

    /** The first finished assignment's note, else the first note, cut to 200 characters. */
    private static String shortNote(List<Document> notes) {
        String fallback = "";
        for (Document entry : notes) {
            String candidate = entry.getString("note");
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String status = entry.getString("status");
            if (AssignmentState.SUCCEEDED.name().equals(status) || AssignmentState.FAILED.name().equals(status)) {
                return trimNote(candidate);
            }
            if (fallback.isEmpty()) {
                fallback = candidate;
            }
        }
        return trimNote(fallback);
    }

    private static String trimNote(String note) {
        return note.length() <= 200 ? note : note.substring(0, 200);
    }

    private static String pageCursor(JobSummary summary) {
        return summary.submittedAt().toEpochMilli() + "/" + summary.jobId();
    }

    private static PageCursor pageCursor(String cursor) {
        int slash = cursor.indexOf('/');
        if (slash <= 0 || slash == cursor.length() - 1) {
            throw badRequest("cursor");
        }
        long millis;
        try {
            millis = Long.parseLong(cursor.substring(0, slash));
            UUID.fromString(cursor.substring(slash + 1));
        } catch (IllegalArgumentException ex) {
            throw badRequest("cursor");
        }
        return new PageCursor(millis, cursor.substring(slash + 1));
    }

    private record PageCursor(long millis, String jobId) {
    }

    @Override
    public AssignmentPage allAssignments(int limit, String cursor) {
        if (limit < 1 || limit > 200) {
            throw badRequest("limit");
        }
        PageCursor after = cursor == null || cursor.isBlank() ? null : pageCursor(cursor);
        List<Assignment> rows = assignments.newest(limit + 1,
                after == null ? null : Instant.ofEpochMilli(after.millis()), after == null ? null : after.jobId());
        List<Assignment> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (rows.size() > limit) {
            Assignment last = page.getLast();
            next = last.createdAt.toEpochMilli() + "/" + last.id;
        }
        return new AssignmentPage(page.stream().map(JobStoreService::viewAssignment).toList(), next);
    }

    @Override
    public List<AssignmentView> poll(String deviceId, Duration wait) {
        if (devices.findById(deviceId) == null) {
            throw notFound("device");
        }
        long deadline = System.nanoTime() + (wait == null ? Duration.ZERO : wait).toNanos();
        List<List<Assignment>> queued = new ArrayList<>(List.of(assignments.queuedFor(deviceId)));
        waitUntil(deadline, () -> {
            if (queued.getFirst().isEmpty()) {
                queued.set(0, assignments.queuedFor(deviceId));
            }
            return !queued.getFirst().isEmpty();
        });
        return queued.getFirst().stream().map(JobStoreService::viewAssignment).toList();
    }

    @Override
    public AssignmentView assignment(String deviceId, UUID assignmentId) {
        return viewAssignment(own(deviceId, assignmentId.toString()));
    }

    @Override
    public AssignmentView claim(String deviceId, UUID assignmentId) {
        return move(deviceId, assignmentId, EnumSet.of(AssignmentState.QUEUED), AssignmentState.CLAIMED, null, null);
    }

    @Override
    public AssignmentView update(String deviceId, UUID assignmentId, UpdateAssignmentRequest request) {
        AssignmentState to = request.state();
        Set<AssignmentState> from = switch (to) {
            case RUNNING -> EnumSet.of(AssignmentState.CLAIMED);
            case SUCCEEDED -> EnumSet.of(AssignmentState.RUNNING);
            case FAILED -> EnumSet.of(AssignmentState.RUNNING, AssignmentState.CLAIMED);
            case EXPIRED -> AssignmentRepository.OPEN;
            case QUEUED, CLAIMED -> {
                own(deviceId, assignmentId.toString());
                throw conflict("cannot move to " + to);
            }
        };
        String session = to == AssignmentState.RUNNING && request.grokSessionId() != null
                ? request.grokSessionId().toString()
                : null;
        return move(deviceId, assignmentId, from, to, request.note(), session);
    }

    /**
     * One conditional write. When nothing matched, one read says why: no such assignment,
     * another device's assignment, or a state the move does not start from.
     */
    private AssignmentView move(String deviceId, UUID assignmentId, Set<AssignmentState> from, AssignmentState to,
                                String note, String sessionId) {
        String id = assignmentId.toString();
        Assignment moved = assignments.transition(id, deviceId, from, to, note, sessionId, clock.instant());
        if (moved == null) {
            Assignment row = own(deviceId, id);
            throw conflict("assignment is " + row.status);
        }
        return viewAssignment(moved);
    }

    private void expireOpen(String jobId) {
        Instant now = clock.instant();
        for (Assignment row : assignments.forJob(jobId)) {
            if (!row.terminal()) {
                assignments.transition(row.id, row.deviceId, AssignmentRepository.OPEN, AssignmentState.EXPIRED,
                        null, null, now);
            }
        }
    }

    private boolean allTerminal(String jobId) {
        List<Assignment> rows = assignments.forJob(jobId);
        return !rows.isEmpty() && rows.stream().allMatch(Assignment::terminal);
    }

    /**
     * Re-reads until {@code done} or the deadline. The pause starts at 250 ms and doubles to 1 s.
     *
     * @return whether {@code done} answered true before the deadline
     */
    private static boolean waitUntil(long deadlineNanos, BooleanSupplier done) {
        long pause = FIRST_WAIT_MILLIS;
        while (!done.getAsBoolean()) {
            long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(pause, remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw conflict("wait interrupted");
            }
            pause = Math.min(pause * 2, LONGEST_WAIT_MILLIS);
        }
        return true;
    }

    private Assignment own(String deviceId, String assignmentId) {
        Assignment row = assignments.findById(assignmentId);
        if (row == null) {
            throw notFound("assignment");
        }
        if (!row.deviceId.equals(deviceId)) {
            throw new DispatcherException(DispatcherException.Kind.FORBIDDEN, "assignment belongs to another device");
        }
        return row;
    }

    private JobView viewJob(String jobId, String dispatcherSubject) {
        return viewJob(jobId, dispatcherSubject, assignments.forJob(jobId));
    }

    private static JobView viewJob(String jobId, String dispatcherSubject, List<Assignment> rows) {
        if (rows.isEmpty() || !dispatcherSubject.equals(rows.getFirst().dispatcherSubject)) {
            throw notFound("job");
        }
        Assignment first = rows.getFirst();
        return new JobView(
                UUID.fromString(jobId),
                dispatcherSubject,
                first.channel,
                first.prompt,
                rows.stream().map(JobStoreService::viewAssignment).toList());
    }

    private static AssignmentView viewAssignment(Assignment row) {
        return new AssignmentView(
                UUID.fromString(row.id),
                UUID.fromString(row.jobId),
                row.deviceId,
                row.agentName,
                row.channel,
                row.prompt,
                row.status,
                row.sessionId == null || row.sessionId.isBlank() ? null : UUID.fromString(row.sessionId),
                row.note,
                row.onBusy == null ? OnBusy.supersede : row.onBusy);
    }

    private static DeviceView viewDevice(Device device) {
        return new DeviceView(device.id, device.hostname, List.copyOf(orEmpty(device.agents)), device.lastHeartbeat,
                Map.copyOf(orEmpty(device.activity)));
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, String> orEmpty(Map<String, String> map) {
        return map == null ? Map.of() : map;
    }

    private static DispatcherException badRequest(String message) {
        return new DispatcherException(DispatcherException.Kind.BAD_REQUEST, message);
    }

    private static DispatcherException notFound(String what) {
        return new DispatcherException(DispatcherException.Kind.NOT_FOUND, what + " not found");
    }

    private static DispatcherException conflict(String message) {
        return new DispatcherException(DispatcherException.Kind.CONFLICT, message);
    }
}
