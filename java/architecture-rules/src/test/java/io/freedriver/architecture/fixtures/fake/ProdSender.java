package io.freedriver.architecture.fixtures.fake;

import io.quarkus.arc.profile.IfBuildProfile;

import java.util.ArrayList;
import java.util.List;

/** A class the prod profile builds: the build profile exempts nothing here. */
@IfBuildProfile("prod")
public class ProdSender {

    private final List<String> sent = new ArrayList<>();

    public void send(String message) {
        sent.add(message);
    }
}
