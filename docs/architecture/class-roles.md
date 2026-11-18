# Class roles: a rule told in prose becomes a check

[#555](https://github.com/kazetsukaimiko/freedriver-web/issues/555) turns the rules agents are told in prose into checks that CI runs. This page follows the Java architecture rules through the three steps that made them checks, and ends with the state of `java/agent-dispatcher` after the third.

## Step 1: the review prompt re-checks imports on every pull request

The layering rules lived in `.github/grok-review-prompt.md`. Line 26 on `main` at `1e72957`:

> - HTTP / JAX-RS, authn, roles, CSRF, rate-limit, wire validation, and exception-to-status mapping are instrumentation. They live at the edge: resource, ContainerRequestFilter, ExceptionMapper, annotation. Application services, backends, snapshots, and domain DTOs stay free of those types.

A model read each pull request's imports, signatures and `Response` construction against that list. [#560](https://github.com/kazetsukaimiko/freedriver-web/issues/560) gives the reason to move on: the prompt "spends most of its 2,173 words on facts about imports, annotations and return types. A model re-checks them on every pull request, with no guarantee, and repeats the same finding on every pull request that touches an old file."

## Step 2: ArchUnit rules by class name, with stores that only shrink

#560 made the nine layering rows ArchUnit rules in `java/architecture-rules` (`LayeringRules`, run by each module's `ArchitectureTest`). A rule finds the edge by name: `*Resource`, `*Api`, `*Filter`, `*Mapper(s)`, `*Tools`, `*Repository`. The violations on `main` that day are frozen under each module's `src/test/resources/archunit-store`, so only a new violation fails `test`. [#585](https://github.com/kazetsukaimiko/freedriver-web/issues/585) closes the way around: a pull request that adds a line to a store fails a step in the `test` job, so the stores only shrink.

A name decides what a class is. A rename moves a class in or out of the edge, and a class whose name matches no pattern is never asked what it is.

## Step 3: declared roles on packages, and the no-state rule

[#608](https://github.com/kazetsukaimiko/freedriver-web/issues/608) has every main class say what it is with one of jMolecules' hexagonal architecture annotations (`org.jmolecules.architecture.hexagonal`, 1.10.0). The role sits on the package's `package-info.java`, and a class carries one only when its role differs from its package's. A nested class takes the role of the class around it.

`RoleRules` in `java/architecture-rules` holds the rules, and a module's `ClassRolesTest` (extending `RolesTest`) runs them against its `target/classes` with no frozen violations:

1. Every top-level class has exactly one role, its own or its package's.
2. jMolecules' hexagonal rules hold, strict, through `jmolecules-archunit` 0.33.0: adapters depend on ports, and the application core depends on ports alone.
3. An `@Application` or adapter class keeps no state in process memory: every field is final, and no field holds a mutable collection or map, an atomic, a lock, an executor or a `CompletableFuture`, unless the field or its class carries `@ProcessLocal("<reason>")` from `java/stereotypes`.
4. Classes with `@IfBuildProfile` for dev or test builds only (fakes) stay out of the judged classes, so the rules judge the prod jar.

## One rule in three forms

### As prose

`.github/grok-review-prompt.md`, lines 80 and 81 on `main` at `1e72957`:

> - Persistence and in-process maps: when the ticket asked for a durable store, Mongo or the existing database is the source of truth. ConcurrentHashMap and sibling in-process maps are a transparent cache: named keys, invalidation, expiry. A dual-write archive beside those maps, with the maps remaining the runtime store, is a should-fix.
> - Scalability: horizontal (replicas, more than one dispatcher) and vertical (jobs, agents, hosts growing). A design that only works as one process holding the maps is a should-fix when the ticket asked for a durable store.

### As code

`java/architecture-rules/src/main/java/io/freedriver/architecture/RoleRules.java`:

```java
public static ArchRule noStateInProcessMemory() {
    return classes().that(DescribedPredicate.describe(
                    "are declared in a source file and are @Application or an adapter",
                    c -> declared(c) && role(c).filter(STATELESS::contains).isPresent()))
            .should(keepNoState())
            .because("state in one replica's memory breaks under a second replica or a restart: keep it in "
                    + "Mongo or Redis, or declare it with @ProcessLocal(\"<why one process is enough>\") "
                    + "(io.freedriver.stereotypes) on the field")
            .allowEmptyShould(true);
}
```

### As a failing `test`

A `ConcurrentHashMap` field added to `JobStoreService` (`@Application`), on Java 23, gives this line from `ClassRolesTest`:

```text
Field <io.freedriver.dispatcher.JobStoreService.waiters> holds a java.util.concurrent.ConcurrentHashMap; declare it with @ProcessLocal("<reason>") or keep the state out of process memory
```

With `@ProcessLocal("…")` on that field, `ClassRolesTest` passes. The one declared field in agent-dispatcher today is `OpenBotJobPing.webhook`, the REST client that class builds on first use.

## The annotations, counted

| Module | Top-level classes | `package-info.java` files | Classes with a role of their own |
| --- | --- | --- | --- |
| `java/agent-dispatcher` | 25 | 1 (`@PrimaryAdapter`) | 17 |
| `java/agent-dispatcher-api` | 4 | 1 (`@PrimaryPort`) | 0 |
| `java/agent-dispatcher-model` | 20 | 1 (`@PrimaryPort`) | 0 |

Three package files give 32 of 49 classes their role. The 6 nested classes of agent-dispatcher (`DispatcherConfig.Client`, `DispatcherException.Kind`, `HttpsWebhookAddress.Rule`, `JobStoreService.PageCursor`, `JobStoreService.Queued`, `WebhookRefused.Mapper`) take the role of the class around them.

agent-dispatcher's main code is one package, so its package role is the role the most classes share that a class can override. A `@PrimaryPort` or `@SecondaryPort` package puts every class in it in that port whatever the class carries, and rule 1 fails such a class.

| Role | agent-dispatcher classes |
| --- | --- |
| `@PrimaryAdapter` (package) | `AllowedClientFilter`, `DispatcherAdminResource`, `DispatcherResource`, `DispatcherToolRoles`, `DispatcherTools`, `ErrorMappers`, `HostResource`, `JobCallers` |
| `@PrimaryPort` | `JobStore`, `DispatcherException` |
| `@Application` | `JobStoreService` |
| `@SecondaryPort` | `JobPing`, `AssignmentRepository`, `DeviceRepository`, `SubmitLocks`, `Assignment`, `Device`, `SubmitLock`, `HttpsWebhookAddress` |
| `@Port` | `DispatcherConfig`, which primary adapters, secondary adapters and the core all read |
| `@SecondaryAdapter` | `OpenBotJobPing`, `OpenBotWebhook`, `OpenBotSignature`, `WebhookRefused`, `JobIndexes` |

## Running the rules

On any JDK, from the repository root: `./mvnw -pl java/agent-dispatcher -am test -Dtest=ClassRolesTest,RoleRulesTest -Dsurefire.failIfNoSpecifiedTests=false`. `RoleRulesTest` in `java/architecture-rules` runs each rule on fixture classes and shows it fails on a class with no role or two, on a `ConcurrentHashMap` field, on a field that changes after construction and on a static map built at run time, and passes with `@ProcessLocal`.
