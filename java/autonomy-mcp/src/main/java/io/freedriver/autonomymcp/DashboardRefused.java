package io.freedriver.autonomymcp;

/** The dashboard answered 400 or more. The status picks the spoken refusal. */
public class DashboardRefused extends RuntimeException {

    private final int status;
    private final boolean pendingCap;

    public DashboardRefused(int status) {
        this(status, false);
    }

    /**
     * @param pendingCap true only when {@link io.freedriver.dashboard.api.PendingCapHeader#NAME}
     *     equals {@link io.freedriver.dashboard.api.PendingCapHeader#FULL}
     */
    public DashboardRefused(int status, boolean pendingCap) {
        super("dashboard answered " + status, null, false, false);
        this.status = status;
        this.pendingCap = pendingCap;
    }

    public int status() {
        return status;
    }

    /** True when the dashboard marked a full pending cap on this refusal. */
    public boolean pendingCap() {
        return pendingCap;
    }
}
