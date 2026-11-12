package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AgentRosterEntry;
import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes {@link JobDocuments} on every call. {@link MongoJobStore} is the production bean.
 * A submit wait and a device poll share {@link JobDocuments#waitLock()} with every other store on these documents.
 */
class DocumentJobStore implements JobStore {

    private static final Duration LOCK_TTL = Duration.ofMinutes(3);

    private final Clock clock;
    private final JobPing jobPing;
    private final JobDocuments documents;

    DocumentJobStore(JobDocuments documents, Clock clock, JobPing jobPing) {
        this.documents = documents;
        this.clock = clock;
        this.jobPing = jobPing;
    }

    private JobPing ping() {
        return jobPing == null ? JobPing.NONE : jobPing;
    }

    /** Submit waits and device polls on every store that shares {@link #documents}. */
    private Object monitor() {
        return documents.waitLock();
    }

    public DeviceView register(String deviceId, String hostname, List<String> agents) {
        requireText(deviceId, "deviceId");
        requireText(hostname, "hostname");
        if (agents == null || agents.isEmpty()) {
            throw badRequest("agents are required");
        }
        List<String> copy = new ArrayList<>();
        for (String agent : agents) {
            requireText(agent, "agent");
            if (copy.contains(agent)) {
                throw badRequest("duplicate agent");
            }
            copy.add(agent);
        }
        synchronized (monitor()) {
            Document device = new Document("_id", deviceId)
                    .append("hostname", hostname)
                    .append("agents", List.copyOf(copy))
                    .append("activity", new Document())
                    .append("activityText", new Document())
                    .append("lastHeartbeat", Date.from(clock.instant()));
            documents.replace(JobDocuments.DEVICES, deviceId, device);
            documents.wake();
            return viewDevice(device);
        }
    }

    /**
     * Host publish. Agent order is the list order. State is idle or busy.
     * Activity is a short note and may be empty.
     */
    public DeviceView report(String deviceId, String hostname, List<ReportedBot> bots) {
        if (bots == null || bots.isEmpty()) {
            throw badRequest("agents are required");
        }
        if (bots.size() > 32) {
            throw badRequest("too many agents");
        }
        Map<String, String> states = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (ReportedBot bot : bots) {
            if (bot == null) {
                throw badRequest("agent is required");
            }
            requireText(bot.name(), "agent");
            if (bot.name().length() > 64) {
                throw badRequest("agent is too long");
            }
            String state = normalizeState(bot.state());
            if (state == null) {
                throw badRequest("state must be idle or busy");
            }
            String note = bot.activity() == null ? "" : bot.activity();
            if (note.length() > 500) {
                throw badRequest("activity is too long");
            }
            if (states.putIfAbsent(bot.name(), state) != null) {
                throw badRequest("duplicate agent");
            }
            notes.put(bot.name(), note);
        }
        requireText(hostname, "hostname");
        if (hostname.length() > 253) {
            throw badRequest("hostname is too long");
        }
        register(deviceId, hostname, new ArrayList<>(states.keySet()));
        synchronized (monitor()) {
            Document device = mustDevice(deviceId);
            device.put("activity", new Document(states));
            device.put("activityText", new Document(notes));
            documents.replace(JobDocuments.DEVICES, deviceId, device);
            return viewDevice(device);
        }
    }

    public List<AgentRosterEntry> listAgents() {
        List<AgentRosterEntry> rows = new ArrayList<>();
        for (Document device : documents.find(JobDocuments.DEVICES)) {
            Map<String, String> activity = map(device.get("activity"));
            Map<String, String> notes = map(device.get("activityText"));
            for (String agent : strings(device.get("agents"))) {
                String stored = activity.get(agent);
                String state = "busy".equals(stored) ? "BUSY" : "IDLE";
                rows.add(new AgentRosterEntry(
                        "bot", agent, device.getString("hostname"), state, notes.getOrDefault(agent, "")));
            }
        }
        return List.copyOf(rows);
    }

    /** The one device whose hostname and agent match. */
    public String deviceIdFor(String server, String agentName) {
        requireText(server, "server");
        requireText(agentName, "agentName");
        String found = null;
        for (Document device : documents.find(JobDocuments.DEVICES)) {
            if (server.equals(device.getString("hostname")) && strings(device.get("agents")).contains(agentName)) {
                if (found != null) {
                    throw badRequest("server matches more than one host");
                }
                found = device.getString("_id");
            }
        }
        if (found == null) {
            throw badRequest("unknown agent " + agentName + " on " + server);
        }
        return found;
    }

    private static String normalizeState(String raw) {
        if (raw == null) {
            return null;
        }
        String state = raw.toLowerCase();
        if ("idle".equals(state) || "busy".equals(state)) {
            return state;
        }
        return null;
    }

    /**
     * One step from queued to a terminal result, so a poller can post the reply
     * without a claim sequence.
     */
    public AssignmentView finish(String deviceId, UUID assignmentId, boolean succeeded, String note) {
        if (note != null && note.length() > 2000) {
            throw badRequest("note is too long");
        }
        synchronized (monitor()) {
            Document row = own(deviceId, assignmentId);
            String status = row.getString("status");
            if (isTerminal(status)) {
                throw conflict("assignment is " + status);
            }
            if ("QUEUED".equals(status)) {
                status = "CLAIMED";
            }
            if ("CLAIMED".equals(status)) {
                status = "RUNNING";
            }
            if (!"RUNNING".equals(status)) {
                throw conflict("assignment is " + row.getString("status"));
            }
            Document next = new Document(row);
            next.put("status", succeeded ? "SUCCEEDED" : "FAILED");
            if (note != null && !note.isBlank()) {
                next.put("note", note);
            }
            next.put("updatedAt", Date.from(clock.instant()));
            if (!documents.replaceMatching(JobDocuments.JOBS,
                    new Document("_id", id(assignmentId)).append("status", row.getString("status")), next)) {
                throw conflict("assignment is " + row.getString("status"));
            }
            documents.wake();
            return viewAssignment(mustJob(assignmentId));
        }
    }

    public DeviceView heartbeat(String deviceId) {
        synchronized (monitor()) {
            Document device = documents.findById(JobDocuments.DEVICES, deviceId);
            if (device == null) {
                throw notFound("device");
            }
            device.put("lastHeartbeat", Date.from(clock.instant()));
            documents.replace(JobDocuments.DEVICES, deviceId, device);
            return viewDevice(device);
        }
    }

    public List<DeviceView> listDevices() {
        List<DeviceView> views = new ArrayList<>();
        for (Document device : documents.find(JobDocuments.DEVICES)) {
            views.add(viewDevice(device));
        }
        return List.copyOf(views);
    }

    public JobView submit(String dispatcherSubject, SubmitJobRequest request, Duration wait) {
        Duration budget = wait == null ? Duration.ZERO : wait;
        long deadline = System.nanoTime() + budget.toNanos();
        Queued queued = enqueue(dispatcherSubject, request, true);
        try {
            notifyHosts(queued.hosts);
            awaitTerminal(queued.jobId, deadline);
            return viewJob(queued.jobId, dispatcherSubject);
        } finally {
            release(dispatcherSubject);
        }
    }

    /**
     * Write the job, return its id, and nudge the OpenBot webhook on another
     * thread. The caller does not wait for that POST. A second send is another row.
     */
    public JobView submitNow(String dispatcherSubject, SubmitJobRequest request) {
        Queued queued = enqueue(dispatcherSubject, request, false);
        Thread.ofVirtual().name("openbot-job-ping").start(() -> notifyHosts(queued.hosts));
        return viewJob(queued.jobId, dispatcherSubject);
    }

    public JobView awaitTurn(String dispatcherSubject, UUID jobId, Duration delay) {
        if (jobId == null) {
            throw badRequest("jobId is required");
        }
        if (delay == null || delay.isNegative() || delay.compareTo(Duration.ofMinutes(2)) > 0) {
            throw badRequest("delay");
        }
        String id = jobId.toString();
        viewJob(id, dispatcherSubject);
        if (!delay.isZero()) {
            long deadline = System.nanoTime() + delay.toNanos();
            while (!allTerminal(id)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                synchronized (monitor()) {
                    try {
                        monitor().wait(millisAtLeastOne(Math.min(remaining, 200_000_000L)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new DispatcherException(DispatcherException.Kind.CONFLICT, "wait interrupted");
                    }
                }
            }
        }
        return viewJob(id, dispatcherSubject);
    }

    private Queued enqueue(String dispatcherSubject, SubmitJobRequest request, boolean lock) {
        requireText(dispatcherSubject, "dispatcher");
        if (request == null) {
            throw badRequest("job is required");
        }
        requireText(request.agentName(), "agentName");
        requireText(request.prompt(), "prompt");
        if (request.channel() == null) {
            throw badRequest("channel is required");
        }
        List<String> deviceIds = request.deviceIds();
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw badRequest("deviceIds are required");
        }
        if (new HashSet<>(deviceIds).size() != deviceIds.size()) {
            throw badRequest("duplicate device");
        }
        synchronized (monitor()) {
            List<String> hosts = new ArrayList<>();
            for (String deviceId : deviceIds) {
                requireText(deviceId, "deviceId");
                Document device = documents.findById(JobDocuments.DEVICES, deviceId);
                if (device == null) {
                    throw badRequest("unknown device " + deviceId);
                }
                if (!strings(device.get("agents")).contains(request.agentName())) {
                    throw badRequest("unknown agent " + request.agentName() + " on " + deviceId);
                }
                String hostname = device.getString("hostname");
                if (!hosts.contains(hostname)) {
                    hosts.add(hostname);
                }
            }
            if (lock) {
                acquire(dispatcherSubject);
            }
            String jobId = UUID.randomUUID().toString();
            List<String> written = new ArrayList<>();
            try {
                int ordinal = 0;
                Instant now = clock.instant();
                for (String deviceId : deviceIds) {
                    String assignmentId = UUID.randomUUID().toString();
                    Document row = new Document("_id", assignmentId)
                            .append("assignmentId", assignmentId)
                            .append("jobId", jobId)
                            .append("deviceId", deviceId)
                            .append("agentName", request.agentName())
                            .append("dispatcherSubject", dispatcherSubject)
                            .append("prompt", request.prompt())
                            .append("channel", request.channel().name())
                            .append("on_busy", onBusy(request).name())
                            .append("status", "QUEUED")
                            .append("ordinal", ordinal++)
                            .append("createdAt", Date.from(now))
                            .append("updatedAt", Date.from(now));
                    documents.replace(JobDocuments.JOBS, assignmentId, row);
                    written.add(assignmentId);
                }
                documents.wake();
                return new Queued(jobId, hosts);
            } catch (RuntimeException failure) {
                for (String assignmentId : written) {
                    documents.delete(JobDocuments.JOBS, assignmentId);
                }
                if (lock) {
                    release(dispatcherSubject);
                }
                throw failure;
            }
        }
    }

    private void notifyHosts(List<String> hosts) {
        for (String hostname : hosts) {
            try {
                ping().queued(hostname);
            } catch (RuntimeException failure) {
                // A notifier bug must not drop a job that is already queued.
            }
        }
    }

    private void acquire(String dispatcherSubject) {
        Instant now = clock.instant();
        Document existing = documents.findById(JobDocuments.LOCKS, dispatcherSubject);
        if (existing != null) {
            Instant expires = instant(existing.get("expiresAt"));
            if (expires != null && expires.isAfter(now)) {
                throw conflict("submit already in flight");
            }
            documents.delete(JobDocuments.LOCKS, dispatcherSubject);
        }
        Document lock = new Document("_id", dispatcherSubject)
                .append("expiresAt", Date.from(now.plus(LOCK_TTL)));
        if (!documents.insertNew(JobDocuments.LOCKS, dispatcherSubject, lock)) {
            throw conflict("submit already in flight");
        }
    }

    private void release(String dispatcherSubject) {
        documents.delete(JobDocuments.LOCKS, dispatcherSubject);
    }

    private record Queued(String jobId, List<String> hosts) {
    }

    @Override
    public int jobCount() {
        Set<String> ids = new HashSet<>();
        for (Document row : documents.find(JobDocuments.JOBS)) {
            if (row.getString("jobId") != null) {
                ids.add(row.getString("jobId"));
            }
        }
        return ids.size();
    }

    public JobView getJob(String dispatcherSubject, UUID jobId) {
        return viewJob(jobId.toString(), dispatcherSubject);
    }

    public List<JobView> listJobs(String dispatcherSubject) {
        requireText(dispatcherSubject, "dispatcher");
        Set<String> seen = new HashSet<>();
        List<JobView> views = new ArrayList<>();
        for (Document row : documents.find(JobDocuments.JOBS)) {
            if (!dispatcherSubject.equals(row.getString("dispatcherSubject"))) {
                continue;
            }
            String jobId = row.getString("jobId");
            if (jobId != null && seen.add(jobId)) {
                views.add(viewJob(jobId, dispatcherSubject));
            }
        }
        return List.copyOf(views);
    }

    public List<AssignmentView> poll(String deviceId, Duration wait) {
        Duration budget = wait == null ? Duration.ZERO : wait;
        if (documents.findById(JobDocuments.DEVICES, deviceId) == null) {
            throw notFound("device");
        }
        long deadline = System.nanoTime() + budget.toNanos();
        List<Document> queued = queuedFor(deviceId);
        while (queued.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            synchronized (monitor()) {
                try {
                    monitor().wait(millisAtLeastOne(Math.min(remaining, 200_000_000L)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DispatcherException(DispatcherException.Kind.CONFLICT, "wait interrupted");
                }
            }
            queued = queuedFor(deviceId);
        }
        List<AssignmentView> views = new ArrayList<>();
        for (Document row : queued) {
            views.add(viewAssignment(row));
        }
        return List.copyOf(views);
    }

    public AssignmentView assignment(String deviceId, UUID assignmentId) {
        return viewAssignment(own(deviceId, assignmentId));
    }

    public AssignmentView claim(String deviceId, UUID assignmentId) {
        synchronized (monitor()) {
            Document row = own(deviceId, assignmentId);
            if (!"QUEUED".equals(row.getString("status"))) {
                throw conflict("assignment is " + row.getString("status"));
            }
            Document next = new Document(row);
            next.put("status", "CLAIMED");
            next.put("updatedAt", Date.from(clock.instant()));
            if (!documents.replaceMatching(JobDocuments.JOBS,
                    new Document("_id", id(assignmentId)).append("status", "QUEUED"), next)) {
                throw conflict("assignment is " + row.getString("status"));
            }
            documents.wake();
            return viewAssignment(mustJob(assignmentId));
        }
    }

    public AssignmentView update(String deviceId, UUID assignmentId, UpdateAssignmentRequest request) {
        if (request == null || request.state() == null) {
            throw badRequest("state is required");
        }
        synchronized (monitor()) {
            Document row = own(deviceId, assignmentId);
            String status = row.getString("status");
            switch (request.state()) {
                case RUNNING -> {
                    if (!"CLAIMED".equals(status)) {
                        throw conflict("assignment is " + status);
                    }
                }
                case SUCCEEDED -> {
                    if (!"RUNNING".equals(status)) {
                        throw conflict("assignment is " + status);
                    }
                }
                case FAILED -> {
                    if (!"RUNNING".equals(status) && !"CLAIMED".equals(status)) {
                        throw conflict("assignment is " + status);
                    }
                }
                case EXPIRED -> {
                    if (isTerminal(status)) {
                        throw conflict("assignment is " + status);
                    }
                }
                case QUEUED, CLAIMED -> throw conflict("cannot move to " + request.state());
            }
            Document next = new Document(row);
            next.put("status", request.state().name());
            if (request.state() == AssignmentState.RUNNING && request.grokSessionId() != null) {
                next.put("sessionId", request.grokSessionId().toString());
            }
            if (request.note() != null) {
                next.put("note", request.note());
            }
            next.put("updatedAt", Date.from(clock.instant()));
            if (!documents.replaceMatching(JobDocuments.JOBS,
                    new Document("_id", id(assignmentId)).append("status", status), next)) {
                throw conflict("assignment is " + status);
            }
            documents.wake();
            return viewAssignment(mustJob(assignmentId));
        }
    }

    private void awaitTerminal(String jobId, long deadline) {
        while (!allTerminal(jobId)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                expireNonTerminal(jobId);
                return;
            }
            synchronized (monitor()) {
                try {
                    monitor().wait(millisAtLeastOne(Math.min(remaining, 200_000_000L)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DispatcherException(DispatcherException.Kind.CONFLICT, "wait interrupted");
                }
            }
        }
    }

    private void expireNonTerminal(String jobId) {
        for (Document row : jobsFor(jobId)) {
            String status = row.getString("status");
            if (isTerminal(status)) {
                continue;
            }
            Document next = new Document(row);
            next.put("status", "EXPIRED");
            next.put("updatedAt", Date.from(clock.instant()));
            documents.replaceMatching(JobDocuments.JOBS,
                    new Document("_id", row.getString("_id")).append("status", status), next);
        }
        synchronized (monitor()) {
            documents.wake();
        }
    }

    private static long millisAtLeastOne(long nanos) {
        long millis = nanos / 1_000_000L;
        return millis <= 0 ? 1 : millis;
    }

    private boolean allTerminal(String jobId) {
        List<Document> rows = jobsFor(jobId);
        if (rows.isEmpty()) {
            return false;
        }
        for (Document row : rows) {
            if (!isTerminal(row.getString("status"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }

    private List<Document> queuedFor(String deviceId) {
        List<Document> queued = new ArrayList<>();
        for (Document row : documents.findEqual(JobDocuments.JOBS, "deviceId", deviceId)) {
            if ("QUEUED".equals(row.getString("status"))) {
                queued.add(row);
            }
        }
        queued.sort(Comparator.comparingInt(row -> row.getInteger("ordinal", 0)));
        return queued;
    }

    private Document own(String deviceId, UUID assignmentId) {
        Document row = documents.findById(JobDocuments.JOBS, id(assignmentId));
        if (row == null) {
            throw notFound("assignment");
        }
        if (!deviceId.equals(row.getString("deviceId"))) {
            throw new DispatcherException(DispatcherException.Kind.FORBIDDEN, "assignment belongs to another device");
        }
        return row;
    }

    private Document mustDevice(String deviceId) {
        Document device = documents.findById(JobDocuments.DEVICES, deviceId);
        if (device == null) {
            throw notFound("device");
        }
        return device;
    }

    private Document mustJob(UUID assignmentId) {
        Document row = documents.findById(JobDocuments.JOBS, id(assignmentId));
        if (row == null) {
            throw notFound("assignment");
        }
        return row;
    }

    private List<Document> jobsFor(String jobId) {
        List<Document> rows = new ArrayList<>(documents.findEqual(JobDocuments.JOBS, "jobId", jobId));
        rows.sort(Comparator.comparingInt(row -> row.getInteger("ordinal", 0)));
        return rows;
    }

    private DeviceView viewDevice(Document device) {
        return new DeviceView(
                device.getString("_id"),
                device.getString("hostname"),
                strings(device.get("agents")),
                instant(device.get("lastHeartbeat")),
                map(device.get("activity")));
    }

    private JobView viewJob(String jobId, String dispatcherSubject) {
        List<Document> rows = jobsFor(jobId);
        if (rows.isEmpty() || !dispatcherSubject.equals(rows.get(0).getString("dispatcherSubject"))) {
            throw notFound("job");
        }
        List<AssignmentView> views = new ArrayList<>();
        for (Document row : rows) {
            views.add(viewAssignment(row));
        }
        Document first = rows.get(0);
        return new JobView(
                UUID.fromString(jobId),
                dispatcherSubject,
                channel(first.getString("channel")),
                first.getString("prompt"),
                List.copyOf(views));
    }

    private AssignmentView viewAssignment(Document row) {
        String session = row.getString("sessionId");
        return new AssignmentView(
                UUID.fromString(row.getString("assignmentId")),
                UUID.fromString(row.getString("jobId")),
                row.getString("deviceId"),
                row.getString("agentName"),
                channel(row.getString("channel")),
                row.getString("prompt"),
                AssignmentState.valueOf(row.getString("status")),
                session == null || session.isBlank() ? null : UUID.fromString(session),
                row.getString("note"),
                onBusy(row.getString("on_busy")));
    }

    private static OnBusy onBusy(SubmitJobRequest request) {
        OnBusy value = request.onBusy();
        return value == null ? OnBusy.supersede : value;
    }

    private static OnBusy onBusy(String name) {
        if (name == null || name.isBlank()) {
            return OnBusy.supersede;
        }
        return OnBusy.valueOf(name);
    }

    private static Channel channel(String name) {
        return Channel.valueOf(name);
    }

    private static String id(UUID assignmentId) {
        return assignmentId.toString();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                copy.add(item.toString());
            }
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> map(Object value) {
        if (!(value instanceof Document document)) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().toString());
        }
        return Map.copyOf(copy);
    }

    private static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw badRequest(name + " is required");
        }
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
