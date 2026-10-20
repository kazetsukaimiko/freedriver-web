package io.freedriver.app.invites;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class InviteProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("freedriver.invites.dir", "target/invite-tests");
    }
}
