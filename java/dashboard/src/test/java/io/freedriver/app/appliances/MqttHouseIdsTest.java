package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttHouseIdsTest {

    private static final UUID FIRST = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID SECOND = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void comma_list_is_the_configured_ids_in_order_once_each() {
        assertEquals(List.of(FIRST, SECOND), MqttHouseIds.parseList(" " + FIRST + ", ," + SECOND + "," + FIRST));
    }

    @Test
    void blank_list_is_empty_and_a_bad_id_is_refused() {
        assertTrue(MqttHouseIds.parseList("").isEmpty());
        assertTrue(MqttHouseIds.parseList(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MqttHouseIds.parseList("not-a-uuid"));
    }

    @Test
    void missing_password_file_is_empty() {
        assertEquals("", MqttHouseIds.password("/no/such/api.pass"));
    }

    @Test
    void password_file_is_stripped_and_not_logged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("api.pass");
        Files.writeString(file, "secret-value\n");
        assertEquals("secret-value", MqttHouseIds.password(file.toString()));
    }
}
