package io.freedriver.rolefixture.api;

import io.freedriver.rolefixture.service.Helper;

/** An API record that names service code of its own module. */
public record UsesService(Helper helper) {
}
