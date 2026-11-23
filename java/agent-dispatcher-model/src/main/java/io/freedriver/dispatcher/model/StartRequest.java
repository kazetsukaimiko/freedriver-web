package io.freedriver.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A host's start of one job: where it runs the job, and whether it watches the job to its end.
 *
 * <p>{@code deliveryRef} is where the host runs the job: 1 to 128 printable ASCII characters, no space.
 *
 * <p>{@code fireAndForget} is optional. Absent or false is watch mode: the host follows the job
 * and reports its finish. True is fire-and-forget: the host delivers the job, starts it and keeps
 * no watch, so the dispatcher sets a finish deadline, {@link AssignmentView#finishBy()}. The
 * deadline is the accepted start plus the dispatcher's finish-within setting (10 minutes by
 * default). A host never sends a duration. The dispatcher fails a fire-and-forget job that nobody
 * finishes by its deadline; see {@link AssignmentView}.
 *
 * <p>The first accepted start fixes the job's mode and its {@code finishBy}. A repeated start with
 * the same {@code deliveryRef} returns the existing assignment unchanged, including when the
 * repeated {@code fireAndForget} differs from the first or is omitted. A start with a different
 * {@code deliveryRef} is a conflict.
 *
 * <p>The JSON body is an object. A bare JSON string is rejected.
 */
public record StartRequest(
        @NotNull @Size(min = 1, max = 128) @Pattern(regexp = StartRequest.DELIVERY_REF) String deliveryRef,
        boolean fireAndForget) {

    /** Printable ASCII, no space. */
    public static final String DELIVERY_REF = "[\\x21-\\x7E]+";

    /**
     * A watch-mode start. It keeps callers written before {@code fireAndForget} compiling; #736
     * moves them to the canonical constructor and deletes this one. JSON never binds through it.
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public StartRequest(String deliveryRef) {
        this(deliveryRef, false);
    }
}
