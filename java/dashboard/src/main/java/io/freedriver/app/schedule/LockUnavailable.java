package io.freedriver.app.schedule;

/** Another holder already has this lock. The caller skips the pass. */
final class LockUnavailable extends RuntimeException {

    LockUnavailable() {
    }
}
