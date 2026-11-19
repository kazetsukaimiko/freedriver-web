/**
 * Fixtures for the unqualified roles. The package's role is one none of these classes carries, so a
 * class that repeats {@code @Port} is still reported for the unqualified port and not as redundant.
 */
@Application
package io.freedriver.architecture.fixtures.unqualified;

import org.jmolecules.architecture.hexagonal.Application;
