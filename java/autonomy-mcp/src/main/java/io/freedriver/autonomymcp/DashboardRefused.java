package io.freedriver.autonomymcp;

/** The dashboard answered 400 or more. The status picks the spoken refusal. */
public class DashboardRefused extends RuntimeException {

    private final int status;

    public DashboardRefused(int status) {
        super("dashboard answered " + status, null, false, false);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
