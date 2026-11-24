package io.freedriver.rolefixture.api.dto;

import io.freedriver.rolefixture.api.Port;

/** A contract subtype below an API package. */
public record Shape(String name) implements Port {
}
