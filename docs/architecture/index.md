# Architecture rules

Public rules this repository already states. The private policy repository stays off this site. [Class roles](class-roles.md) is the package-role rule, included below and linked on its own page.

## Packages

A class's role comes from its package. `io.freedriver.<project>[.<area>].api` holds contracts. `.impl.<name>` holds implementations. `.service` holds service code. A module holds one role. Panache entities and repositories, and the Keycloak admin client, live in `impl.<name>` packages behind domain-shaped store APIs. ArchUnit enforces the layering rules in `mvn verify`.

## HTTP between services

The JAX-RS interface in an `api` package is the contract. The server implements it. A caller injects a REST client built from that interface with `quarkus-rest-client-jackson`.

## Roles on a contract

An interface whose callers are signed-in users carries `@RolesAllowed`, and a REST client built from it checks that same user before the request leaves. An interface whose callers are services with their own credentials leaves `@RolesAllowed` on the resource class, and Caddy answers 404 on its path. The dispatcher's host API (`/api/dispatcher/host`) is the exception: the host runs outside the compose network, so Caddy routes it, and `HostResource` carries `@RolesAllowed("device")`.

## Keycloak, Mongo, Redis

The Keycloak admin client lives in an `impl.<name>` package behind a domain-shaped store API. `quarkus-oidc-client` obtains service-account tokens.

Panache entities and repositories live in an `impl.<name>` package behind a domain-shaped store API. Filters, sorts, limits, and atomic updates run in Mongo. The repository's module creates its indexes at startup.

Each service reaches Redis through `quarkus-redis-client` and its injected `RedisDataSource`.

## MCP

A tool is a `@Tool` method on `quarkus-mcp-server`. Bean Validation constraints on its arguments are the validation and the input schema. Security annotations carry its role checks. A tool call runs with the permissions of the user who connected.

## Plain Java

`sms-client` runs inside Keycloak and `device-daemon` runs on user devices. Both use the JDK HTTP client and Jackson.

## Class roles

--8<-- "docs/architecture/class-roles.md"
