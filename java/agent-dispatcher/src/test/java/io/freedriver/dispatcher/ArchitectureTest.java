package io.freedriver.dispatcher;

import io.freedriver.architecture.LayeringTest;

/**
 * The Java layering rules (#560) for this module. Violations recorded in
 * src/test/resources/archunit-store pass; a new one fails with the fix in its message.
 */
class ArchitectureTest extends LayeringTest {

    @Override
    protected String exceptionPackage() {
        return "io.freedriver.dispatcher";
    }
}
