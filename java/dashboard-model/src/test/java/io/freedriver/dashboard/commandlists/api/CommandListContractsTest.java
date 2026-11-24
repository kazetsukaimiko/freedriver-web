package io.freedriver.dashboard.commandlists.api;

import io.freedriver.dashboard.model.api.ApplianceCommandListRequest;
import io.freedriver.mqtt.contract.ScheduledCommandsMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command-list store and reader contracts (#637, pull request 1). */
class CommandListContractsTest {

    @Test
    void latestScheduledStoresAStrictlyNewerSnapshot() throws Exception {
        Method replace = LatestScheduled.class.getMethod(
                "replaceIfNewer", UUID.class, ScheduledCommandsMessage.class);
        assertEquals(boolean.class, replace.getReturnType());
        assertEquals(0, replace.getExceptionTypes().length);

        Method latest = LatestScheduled.class.getMethod("latest", UUID.class);
        assertEquals(Optional.class, latest.getReturnType());
        ParameterizedType generic = (ParameterizedType) latest.getGenericReturnType();
        assertEquals(ScheduledCommandsMessage.class, generic.getActualTypeArguments()[0]);
        assertEquals(0, latest.getExceptionTypes().length);
    }

    @Test
    void commandListReaderRefusesWithInvalidCommandListException() throws Exception {
        Method read = CommandListReader.class.getMethod("read", InputStream.class);
        assertEquals(ApplianceCommandListRequest.class, read.getReturnType());
        assertArrayEquals(new Class<?>[] {InvalidCommandListException.class}, read.getExceptionTypes());
    }

    @Test
    void refusalKeepsTheMessageAndTheCause() {
        IllegalArgumentException cause = new IllegalArgumentException("unknown field extra");
        InvalidCommandListException refusal = new InvalidCommandListException("Rejected command list", cause);
        assertEquals("Rejected command list", refusal.getMessage());
        assertSame(cause, refusal.getCause());
        assertTrue(refusal instanceof RuntimeException);
        assertEquals("missing commands", new InvalidCommandListException("missing commands").getMessage());
    }
}
