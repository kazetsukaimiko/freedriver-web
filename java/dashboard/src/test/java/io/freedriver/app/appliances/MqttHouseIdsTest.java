package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttHouseIdsTest {

    private static final UUID FIRST = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID SECOND = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void comma_list_wins_over_the_file() {
        assertEquals(List.of(FIRST, SECOND), MqttHouseIds.instances(FIRST + "," + SECOND, "/no/such/acl"));
    }

    @Test
    void missing_file_is_empty() {
        assertTrue(MqttHouseIds.instances("", "/no/such/acl").isEmpty());
        assertEquals("", MqttHouseIds.password("/no/such/api.pass"));
    }

    @Test
    void acl_appliances_topics_are_the_instance_ids(@TempDir Path dir) throws Exception {
        Path acl = dir.resolve("acl");
        Files.writeString(acl, """
                # do not treat this comment uuid as a house: 22222222-2222-2222-2222-222222222222
                user autonomy
                topic write freedriver/v1/%s/appliances
                topic read freedriver/v1/%s/commands
                user api
                topic read freedriver/v1/%s/appliances
                topic write freedriver/v1/%s/commands
                topic read freedriver/v1/__INSTANCE_ID__/appliances
                topic read freedriver/v1/%s/commands
                """.formatted(FIRST, FIRST, FIRST, FIRST, SECOND));
        assertEquals(List.of(FIRST), MqttHouseIds.instances("", acl.toString()));
    }

    @Test
    void plain_uuid_file_is_one_instance(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("instance-ids");
        Files.writeString(file, SECOND + "\n");
        assertEquals(List.of(SECOND), MqttHouseIds.fromFile(file.toString()));
    }

    @Test
    void password_file_is_stripped_and_not_logged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("api.pass");
        Files.writeString(file, "secret-value\n");
        assertEquals("secret-value", MqttHouseIds.password(file.toString()));
    }
}
