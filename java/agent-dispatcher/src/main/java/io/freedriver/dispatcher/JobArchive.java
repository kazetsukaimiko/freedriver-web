package io.freedriver.dispatcher;

import java.time.Instant;
import java.util.List;

/** Copies a queued assignment into the agent-jobs collection. Empty means the copy is skipped. */
interface JobArchive {

    JobArchive NONE = new JobArchive() {
        @Override
        public void save(StoredJob job) {
        }

        @Override
        public List<StoredJob> load() {
            return List.of();
        }
    };

    void save(StoredJob job);

    List<StoredJob> load();

    /**
     * One assignment as stored. {@code note} is the response. Times are UTC.
     */
    record StoredJob(
            String assignmentId,
            String jobId,
            String deviceId,
            String agentName,
            String dispatcherSubject,
            String prompt,
            String channel,
            String status,
            String note,
            String sessionId,
            Instant createdAt,
            Instant updatedAt) {
    }
}
