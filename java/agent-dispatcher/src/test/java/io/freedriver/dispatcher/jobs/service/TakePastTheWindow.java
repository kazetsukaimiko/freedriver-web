package io.freedriver.dispatcher.jobs.service;

import io.freedriver.dispatcher.store.AssignmentRepository;
import io.freedriver.dispatcher.store.DeviceRepository;
import io.freedriver.dispatcher.store.SubmitLocks;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Expires an untaken job the way {@code HostResourceTest} needs: take with a clock past the window.
 * The service constructors stay package-private, and that test lives in another package.
 */
public final class TakePastTheWindow {

    private TakePastTheWindow() {
    }

    public static void take(AssignmentRepository assignments, DeviceRepository devices, SubmitLocks locks,
            String deviceId) {
        new JobStoreService(assignments, devices, locks,
                Clock.offset(Clock.systemUTC(), JobStoreService.DEFAULT_TAKE_WITHIN.plusSeconds(1)), null)
                .take(deviceId, List.of(), Duration.ZERO);
    }
}
