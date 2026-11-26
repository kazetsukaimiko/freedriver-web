package io.freedriver.architecture.fixtures.apiquality;

import java.util.regex.Pattern;

/** A constant holder that compiles a regular expression. */
public final class CompiledPattern {
    public static final Pattern PATTERN = Pattern.compile("a");

    private CompiledPattern() {
    }
}
