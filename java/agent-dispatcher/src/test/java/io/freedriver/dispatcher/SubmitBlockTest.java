package io.freedriver.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitBlockTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omit_and_zero_do_not_wait() throws Exception {
        assertTrue(SubmitBlock.parse(null).isEmpty());
        assertTrue(SubmitBlock.parse(mapper.readTree("null")).isEmpty());
        ObjectNode omitted = mapper.createObjectNode();
        assertTrue(SubmitBlock.parse(omitted.path("blockForUpTo")).isEmpty());
        assertTrue(SubmitBlock.parse(mapper.readTree("0")).isEmpty());
    }

    @Test
    void a_wait_inside_the_cap_is_kept() throws Exception {
        assertEquals(Duration.ofSeconds(30), SubmitBlock.parse(mapper.readTree("30")).orElseThrow());
        assertEquals(Duration.ofSeconds(180), SubmitBlock.parse(mapper.readTree("180")).orElseThrow());
    }

    @Test
    void over_three_minutes_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> SubmitBlock.parse(mapper.readTree("181")));
        assertThrows(IllegalArgumentException.class, () -> SubmitBlock.parse(mapper.readTree("-1")));
        assertThrows(IllegalArgumentException.class, () -> SubmitBlock.parse(mapper.readTree("1.5")));
        assertThrows(IllegalArgumentException.class, () -> SubmitBlock.parse(mapper.readTree("\"30\"")));
    }
}
