package io.freedriver.dashboard.commandlists.api;

import io.freedriver.dashboard.model.api.ApplianceCommandListRequest;

import java.io.InputStream;

/**
 * Strict reader of a list-POST body.
 * <p>
 * The reader matches {@code ApplianceJson}: scalar coercion is off, an unknown property fails,
 * and {@code delay} is a JSON string read with {@link java.time.Duration#parse(CharSequence)}.
 * A body that breaks {@link ApplianceCommandListRequest}'s limits fails the same way.
 * The failure is {@link InvalidCommandListException}. A well-formed body comes back with its
 * commands in the order the JSON gave them.
 * <p>
 * {@code json} is non-null. A null stream is a caller error.
 */
public interface CommandListReader {

    /**
     * Reads one list-POST body from {@code json}.
     *
     * @throws InvalidCommandListException when the body fails the strict read
     */
    ApplianceCommandListRequest read(InputStream json) throws InvalidCommandListException;
}
