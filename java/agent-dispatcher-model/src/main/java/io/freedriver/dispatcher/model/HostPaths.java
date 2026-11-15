package io.freedriver.dispatcher.model;

/**
 * The paths of the dispatcher's host API, shared by {@code HostApi} and the plain-Java host
 * connector. {@link #START} and {@link #FINISH} carry the {@link #ASSIGNMENT_ID} path parameter.
 */
public final class HostPaths {

    /** Root of the host API. */
    public static final String ROOT = "/api/dispatcher/host";
    /** {@code POST}: publish this host's bots. */
    public static final String REPORT = "/report";
    /** {@code POST}: take this host's jobs, each exactly once. */
    public static final String TAKE = "/take";
    /** Name of the assignment id path parameter. */
    public static final String ASSIGNMENT_ID = "assignmentId";
    /** {@code POST}: mark one claimed job running and store where it runs. */
    public static final String START = "/jobs/{" + ASSIGNMENT_ID + "}/start";
    /** {@code POST}: post the result of one job. */
    public static final String FINISH = "/jobs/{" + ASSIGNMENT_ID + "}/finish";

    private HostPaths() {
    }
}
