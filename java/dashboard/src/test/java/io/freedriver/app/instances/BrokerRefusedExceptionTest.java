package io.freedriver.app.instances;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrokerRefusedExceptionTest {

    @Test
    void the_no_argument_constructor_carries_an_empty_reason_and_status() {
        BrokerRefusedException refused = new BrokerRefusedException();

        assertTrue(refused.reason().isEmpty());
        assertEquals(OptionalInt.empty(), refused.status());
        assertEquals("list-failed", refused.logReason());
        assertEquals("broker refused", refused.getMessage());
    }

    @Test
    void each_reason_keeps_its_log_word_and_only_http_carries_a_status() {
        BrokerRefusedException missingSecret = BrokerRefusedException.notConfigured();
        BrokerRefusedException missingGroup = BrokerRefusedException.groupMissing();
        BrokerRefusedException http = BrokerRefusedException.http(404);
        BrokerRefusedException transport = BrokerRefusedException.transport();

        assertEquals(BrokerRefusedException.Reason.NOT_CONFIGURED, missingSecret.reason().orElseThrow());
        assertEquals("not-configured", missingSecret.logReason());
        assertEquals(OptionalInt.empty(), missingSecret.status());

        assertEquals(BrokerRefusedException.Reason.GROUP_MISSING, missingGroup.reason().orElseThrow());
        assertEquals("group-missing", missingGroup.logReason());
        assertEquals(OptionalInt.empty(), missingGroup.status());

        assertEquals(BrokerRefusedException.Reason.HTTP, http.reason().orElseThrow());
        assertEquals("list-failed", http.logReason());
        assertEquals(OptionalInt.of(404), http.status());

        assertEquals(BrokerRefusedException.Reason.TRANSPORT, transport.reason().orElseThrow());
        assertEquals("transport", transport.logReason());
        assertEquals(OptionalInt.empty(), transport.status());

        assertEquals("broker refused", http.getMessage());
        assertFalse(http.getMessage().contains("404"));
    }
}
