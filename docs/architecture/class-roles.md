# Class roles

Issue #555 asked for one architecture rule that an agent can check. It landed in three steps.

The review prompt re-checked imports on every pull request (#560). Principled Engineering Review reads the files a diff touches and flags JAX-RS, `SecurityIdentity`, and HTTP clients that have wandered out of the edge. That check is prose: it runs when a reviewer model reads the prompt, and a later edit can put the same import back.

ArchUnit then checked the same layering by class name, with a frozen store per rule (#560). A violation recorded under `src/test/resources/archunit-store` passes. A new line fails. The store can only shrink: `.github/scripts/archunit_store_check.py` rejects a line the base branch did not already have, unless kazetsukaimiko adds the `freeze-new-violation` label (#585).

Roles replaced the class-name guess (#608, and the package move here). Each main package carries one jMolecules role on `package-info.java`. A class with no annotation is judged as that package's role, so a new class shows its role as the directory it was added to. A class carries its own role only when that role differs from the package. `RoleRules.noStateInProcessMemory` rejects state in process memory on an application or adapter class unless the field or the class carries `@ProcessLocal` with a reason.

One rule in both forms is "every class has one role":

> Put one hexagonal role on the package. A class with no annotation takes that role. A package with no role fails, a package with two roles fails, and a class that repeats its package's role fails. `@Port` on a `@ConfigMapping` interface may repeat the package's `@Port`.

```java
} else if (own.size() == 1 && inPackage.isEmpty()) {
    events.add(SimpleConditionEvent.violated(c, c.getName() + " is in package " + pkg
            + ", which carries no role"));
}
```

A package of one class with its own role and no `package-info` fails `RoleRulesTest` with:

```
io.freedriver.architecture.fixtures.missingpackagerole.Placed is in package io.freedriver.architecture.fixtures.missingpackagerole, which carries no role
```

agent-dispatcher has 25 main classes and 6 package-info files:

| Package | Role | Classes |
| --- | --- | --- |
| `io.freedriver.dispatcher.web` | `@PrimaryAdapter` | `AllowedClientFilter`, `DispatcherAdminResource`, `DispatcherResource`, `DispatcherToolRoles`, `DispatcherTools`, `ErrorMappers`, `HostResource`, `JobCallers` |
| `io.freedriver.dispatcher.jobs` | `@PrimaryPort` | `JobStore` |
| `io.freedriver.dispatcher.jobs.service` | `@Application` | `JobStoreService` |
| `io.freedriver.dispatcher.store` | `@SecondaryPort` | `Assignment`, `AssignmentRepository`, `Device`, `DeviceRepository`, `JobPing`, `SubmitLock`, `SubmitLocks` |
| `io.freedriver.dispatcher.openbot` | `@SecondaryAdapter` | `OpenBotJobPing`, `OpenBotSignature`, `OpenBotWebhook` |
| `io.freedriver.dispatcher` | `@Port` | `DispatcherConfig`, `DispatcherException`, `HttpsWebhookAddress`, `JobIndexes`, `WebhookRefused` |

`DispatcherConfig` inherits `@Port` because it is the `@ConfigMapping`. The other four root classes carry their own role: `DispatcherException` is `@PrimaryPort`, `WebhookRefused` is `@SecondaryAdapter` (`ArchitectureTest.exceptionPackage()` names `io.freedriver.dispatcher`, so the exception stays in that package), `HttpsWebhookAddress` is `@SecondaryPort` on the webhook address, and `JobIndexes` is `@SecondaryAdapter` because the store package is a port and an index creator is an adapter. A probe in `RoleRulesTest` puts a `@SecondaryAdapter` in a `@Port` package and has it call another `@SecondaryAdapter`. jMolecules 0.33.0 judges that class by its own role, so `@Port` stays off `RoleRules.PACKAGE_WIDE`.
