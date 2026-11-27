package io.freedriver.dashboard.commandlists.api;

import io.freedriver.mqtt.contract.ScheduledCommandsMessage;

import java.util.Optional;
import java.util.UUID;

/**
 * The latest scheduled snapshot for one house.
 * <p>
 * A house has one stored snapshot. {@link #replaceIfNewer} stores {@code message} when the house
 * has none, or when {@code message.publishedAt()} is strictly later than the stored snapshot's
 * {@code publishedAt}. An older or equal {@code publishedAt} leaves the stored snapshot unchanged.
 * A snapshot whose pending list is empty is stored the same way as one that lists commands.
 * <p>
 * {@link #replaceIfNewer} is atomic per house across every process that shares the store: the
 * compare and the write are one step, so concurrent calls for one house from any dashboard replica
 * never leave an older snapshot in place of a newer one, and of two calls with the same
 * {@code publishedAt} at most one stores. A store local to one process does not meet this contract.
 * <p>
 * {@code instanceId} and {@code message} are non-null, and {@code message.publishedAt()} is non-null.
 * A null is a caller error.
 */
public interface LatestScheduled {

    /**
     * Stores {@code message} for {@code instanceId} when it is strictly newer than the stored
     * snapshot, or when the house has none.
     *
     * @return true when this call stored {@code message}; false when the stored snapshot is newer
     *         or equal and stays as it was
     */
    boolean replaceIfNewer(UUID instanceId, ScheduledCommandsMessage message);

    /** The stored snapshot for the house, or empty when none is stored. */
    Optional<ScheduledCommandsMessage> latest(UUID instanceId);
}
