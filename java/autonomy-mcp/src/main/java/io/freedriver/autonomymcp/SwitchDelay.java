package io.freedriver.autonomymcp;

/** Shared names for a delayed {@code switch_appliance}. */
final class SwitchDelay {

    static final int MAX_DELAY_SECONDS = 86_400;
    /** Payload remains readable for an hour after the switch is due. */
    static final long PAYLOAD_GRACE_SECONDS = 3600;
    static final long LOCK_SECONDS = 30;
    static final String DUE_KEY = "freedriver:mcp:autonomy:switch-due";
    static final String PAYLOAD_PREFIX = "freedriver:mcp:autonomy:switch:";
    static final String LOCK_PREFIX = "freedriver:mcp:autonomy:switch-lock:";

    private SwitchDelay() {
    }

    static long payloadTtlSeconds(int delaySeconds) {
        return delaySeconds + PAYLOAD_GRACE_SECONDS;
    }
}
