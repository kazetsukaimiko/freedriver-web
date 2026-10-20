package io.freedriver.dispatcher;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory queues. One assignment list per device, one job per dispatcher subject.
 * Lost on restart. Postgres waits until bearer auth is actually on.
 */
@ApplicationScoped
public class JobStore {

    private final Clock clock;
    private final Object monitor = new Object();
    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Assignment> assignments = new ConcurrentHashMap<>();
    /** One in-flight submit per subject. REST and /mcp share this set. */
    private final Set<String> inFlightSubmits = new HashSet<>();

    public JobStore() {
        this(Clock.systemUTC());
    }

    JobStore(Clock clock) {
        this.clock = clock;
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
        synchronized (monitor) {
            Device device = devices.computeIfAbsent(deviceId, Device::new);
            device.hostname = hostname;
            device.agents = List.copyOf(copy);
            device.lastHeartbeat = clock.instant();
            monitor.notifyAll();
            return view(device);
        }
    }

    public DeviceView heartbeat(String deviceId) {
        synchronized (monitor) {
            Device device = devices.get(deviceId);
            if (device == null) {
                throw notFound("device");
            }
            device.lastHeartbeat = clock.instant();
            return view(device);
        }
    }

    public List<DeviceView> listDevices() {
        synchronized (monitor) {
            List<DeviceView> views = new ArrayList<>();
            for (Device device : devices.values()) {
                views.add(view(device));
            }
            return List.copyOf(views);
        }
    }

    public JobView submit(String dispatcherSubject, SubmitJobRequest request, Duration wait) {
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
        Duration budget = wait == null ? Duration.ZERO : wait;
        synchronized (monitor) {
            for (String deviceId : deviceIds) {
                requireText(deviceId, "deviceId");
                Device device = devices.get(deviceId);
                if (device == null) {
                    throw badRequest("unknown device " + deviceId);
                }
                if (!device.agents.contains(request.agentName())) {
                    throw badRequest("unknown agent " + request.agentName() + " on " + deviceId);
                }
            }
            if (!inFlightSubmits.add(dispatcherSubject)) {
                throw conflict("submit already in flight");
            }
            try {
                Job job = new Job(UUID.randomUUID(), dispatcherSubject, request.channel(), request.prompt());
                for (String deviceId : deviceIds) {
                    Assignment assignment = new Assignment(
                            UUID.randomUUID(), job.id, deviceId, request.agentName());
                    job.assignmentIds.add(assignment.id);
                    assignments.put(assignment.id, assignment);
                }
                jobs.put(job.id, job);
                monitor.notifyAll();
                awaitTerminal(job, budget);
                return view(job);
            } finally {
                inFlightSubmits.remove(dispatcherSubject);
            }
        }
    }

    int jobCount() {
        synchronized (monitor) {
            return jobs.size();
        }
    }

    public JobView getJob(String dispatcherSubject, UUID jobId) {
        synchronized (monitor) {
            Job job = jobs.get(jobId);
            if (job == null || !job.dispatcherSubject.equals(dispatcherSubject)) {
                throw notFound("job");
            }
            return view(job);
        }
    }

    public List<AssignmentView> poll(String deviceId, Duration wait) {
        Duration budget = wait == null ? Duration.ZERO : wait;
        synchronized (monitor) {
            if (!devices.containsKey(deviceId)) {
                throw notFound("device");
            }
            long deadline = System.nanoTime() + budget.toNanos();
            List<Assignment> queued = queuedFor(deviceId);
            while (queued.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    monitor.wait(millisAtLeastOne(remaining));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DispatcherException(DispatcherException.Kind.CONFLICT, "wait interrupted");
                }
                queued = queuedFor(deviceId);
            }
            List<AssignmentView> views = new ArrayList<>();
            for (Assignment assignment : queued) {
                views.add(view(assignment));
            }
            return List.copyOf(views);
        }
    }

    public AssignmentView assignment(String deviceId, UUID assignmentId) {
        synchronized (monitor) {
            return view(own(deviceId, assignmentId));
        }
    }

    public AssignmentView claim(String deviceId, UUID assignmentId) {
        synchronized (monitor) {
            Assignment assignment = own(deviceId, assignmentId);
            if (assignment.state != AssignmentState.QUEUED) {
                throw conflict("assignment is " + assignment.state);
            }
            assignment.state = AssignmentState.CLAIMED;
            monitor.notifyAll();
            return view(assignment);
        }
    }

    public AssignmentView update(String deviceId, UUID assignmentId, UpdateAssignmentRequest request) {
        if (request == null || request.state() == null) {
            throw badRequest("state is required");
        }
        synchronized (monitor) {
            Assignment assignment = own(deviceId, assignmentId);
            switch (request.state()) {
                case RUNNING -> {
                    if (assignment.state != AssignmentState.CLAIMED) {
                        throw conflict("assignment is " + assignment.state);
                    }
                    if (request.grokSessionId() == null) {
                        throw badRequest("running requires grokSessionId");
                    }
                    assignment.grokSessionId = request.grokSessionId();
                }
                case SUCCEEDED -> {
                    if (assignment.state != AssignmentState.RUNNING) {
                        throw conflict("assignment is " + assignment.state);
                    }
                }
                // Claimed to failed is a refusal before a session starts (speech, or grok
                // never started). Succeeded still requires running.
                case FAILED -> {
                    if (assignment.state != AssignmentState.RUNNING
                            && assignment.state != AssignmentState.CLAIMED) {
                        throw conflict("assignment is " + assignment.state);
                    }
                }
                case EXPIRED -> {
                    if (isTerminal(assignment.state)) {
                        throw conflict("assignment is " + assignment.state);
                    }
                }
                case QUEUED, CLAIMED -> throw conflict("cannot move to " + request.state());
            }
            assignment.state = request.state();
            if (request.note() != null) {
                assignment.note = request.note();
            }
            monitor.notifyAll();
            return view(assignment);
        }
    }

    private void awaitTerminal(Job job, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (!allTerminal(job)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                // The caller is done waiting. A later claim must not run the prompt.
                expireNonTerminal(job);
                return;
            }
            try {
                monitor.wait(millisAtLeastOne(remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DispatcherException(DispatcherException.Kind.CONFLICT, "wait interrupted");
            }
        }
    }

    private void expireNonTerminal(Job job) {
        for (UUID id : job.assignmentIds) {
            Assignment assignment = assignments.get(id);
            if (!isTerminal(assignment.state)) {
                assignment.state = AssignmentState.EXPIRED;
            }
        }
        monitor.notifyAll();
    }

    private static long millisAtLeastOne(long nanos) {
        long millis = nanos / 1_000_000L;
        return millis <= 0 ? 1 : millis;
    }

    private boolean allTerminal(Job job) {
        for (UUID id : job.assignmentIds) {
            if (!isTerminal(assignments.get(id).state)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminal(AssignmentState state) {
        return state == AssignmentState.SUCCEEDED
                || state == AssignmentState.FAILED
                || state == AssignmentState.EXPIRED;
    }

    private List<Assignment> queuedFor(String deviceId) {
        List<Assignment> queued = new ArrayList<>();
        for (Assignment assignment : assignments.values()) {
            if (deviceId.equals(assignment.deviceId) && assignment.state == AssignmentState.QUEUED) {
                queued.add(assignment);
            }
        }
        return queued;
    }

    private Assignment own(String deviceId, UUID assignmentId) {
        Assignment assignment = assignments.get(assignmentId);
        if (assignment == null) {
            throw notFound("assignment");
        }
        if (!assignment.deviceId.equals(deviceId)) {
            throw new DispatcherException(DispatcherException.Kind.FORBIDDEN, "assignment belongs to another device");
        }
        return assignment;
    }

    private DeviceView view(Device device) {
        return new DeviceView(device.id, device.hostname, device.agents, device.lastHeartbeat);
    }

    private JobView view(Job job) {
        List<AssignmentView> views = new ArrayList<>();
        for (UUID id : job.assignmentIds) {
            views.add(view(assignments.get(id)));
        }
        return new JobView(job.id, job.dispatcherSubject, job.channel, job.prompt, List.copyOf(views));
    }

    private AssignmentView view(Assignment assignment) {
        Job job = jobs.get(assignment.jobId);
        if (job == null) {
            throw notFound("job");
        }
        return new AssignmentView(
                assignment.id,
                assignment.jobId,
                assignment.deviceId,
                assignment.agentName,
                job.channel,
                job.prompt,
                assignment.state,
                assignment.grokSessionId,
                assignment.note);
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

    private static final class Device {
        private final String id;
        private String hostname;
        private List<String> agents = List.of();
        private Instant lastHeartbeat;

        private Device(String id) {
            this.id = id;
        }
    }

    private static final class Job {
        private final UUID id;
        private final String dispatcherSubject;
        private final Channel channel;
        private final String prompt;
        private final List<UUID> assignmentIds = new ArrayList<>();

        private Job(UUID id, String dispatcherSubject, Channel channel, String prompt) {
            this.id = id;
            this.dispatcherSubject = dispatcherSubject;
            this.channel = channel;
            this.prompt = prompt;
        }
    }

    private static final class Assignment {
        private final UUID id;
        private final UUID jobId;
        private final String deviceId;
        private final String agentName;
        private AssignmentState state = AssignmentState.QUEUED;
        private UUID grokSessionId;
        private String note;

        private Assignment(UUID id, UUID jobId, String deviceId, String agentName) {
            this.id = id;
            this.jobId = jobId;
            this.deviceId = deviceId;
            this.agentName = agentName;
        }
    }
}
