package io.freedriver.architecture.fixtures.fake;

import io.quarkus.arc.profile.IfBuildProfile;

import java.util.ArrayList;
import java.util.List;

/** A dev and test fake that records what it sent. */
@IfBuildProfile(anyOf = {"dev", "test"})
public class FakeSender {

    private final List<String> sent = new ArrayList<>();

    public void send(String message) {
        sent.add(message);
    }
}
