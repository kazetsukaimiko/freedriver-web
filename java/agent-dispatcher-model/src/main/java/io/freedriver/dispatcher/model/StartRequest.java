package io.freedriver.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A host's report that one job has started: which delivery carries it, and whether the host
 * watches the job to its end.
 *
 * <p>{@code deliveryRef} is the host's receipt for the delivery. The host has already handed the
 * job's message to the agent, and the agent's runtime answered with an id for that delivery; for
 * an OpenBot agent it is OpenBot's delivery id. The host reports that id here once the delivery is
 * running. The dispatcher only stores it and returns it as {@link AssignmentView#deliveryRef()}:
 * a restarted host reads it there and goes on watching the same delivery, and it tells a repeated
 * start from a second delivery of the same job. 1 to 128 printable ASCII characters, no space.
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
 * <p>The JSON body is an object. A bare JSON string is rejected. A watch-mode start serializes
 * as {@code {"deliveryRef":"…"}} alone: {@code fireAndForget} is written only when true.
 */
public record StartRequest(
        @NotNull @Size(min = 1, max = 128) @Pattern(regexp = StartRequest.DELIVERY_REF) String deliveryRef,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean fireAndForget) {

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
