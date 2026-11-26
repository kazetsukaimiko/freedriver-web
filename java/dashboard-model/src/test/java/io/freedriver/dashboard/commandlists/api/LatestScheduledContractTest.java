package io.freedriver.dashboard.commandlists.api;

import io.freedriver.mqtt.contract.ScheduledCommandsMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The latest-scheduled store contract (#637, pull request 1). */
class LatestScheduledContractTest {

    @Test
    void storesAStrictlyNewerSnapshot() throws Exception {
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
}
