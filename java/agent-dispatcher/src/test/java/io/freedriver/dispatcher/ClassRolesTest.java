package io.freedriver.dispatcher;

import io.freedriver.architecture.RolesTest;

/**
 * The architecture-role rules (#608) for this module, with no frozen violations: every main class
 * has one role, the roles keep the hexagonal rules, and no application or adapter class keeps
 * state in process memory without {@code @ProcessLocal}.
 */
class ClassRolesTest extends RolesTest {
}
