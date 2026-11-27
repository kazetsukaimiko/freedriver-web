package io.freedriver.architecture.fixtures.apiquality;

/** String concatenation and a switch. Those bootstraps are not lambdas. */
public class ConcatHolds {
    String show(String name) {
        return switch (name) {
            case "a" -> "A" + name;
            default -> "B" + name;
        };
    }
}
