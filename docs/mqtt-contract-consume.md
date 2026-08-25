# Consume autonomy-mqtt-contract

Coordinates: `io.freedriver.autonomy:autonomy-mqtt-contract`

Repository: `https://maven.pkg.github.com/kazetsukaimiko/autonomy` (Maven repo id `github`).

Published package: https://github.com/kazetsukaimiko/autonomy/packages/3213073 (`io.freedriver.autonomy.autonomy-mqtt-contract`).

`1.0.0-SNAPSHOT` in git is local-only. Published versions will soon be unique non-SNAPSHOT (`1.0.<run_number>`). Do not invent a version pin until autonomy publishes one.

GitHub Maven packages are repository-scoped. They inherit permissions from the publishing repo (`kazetsukaimiko/autonomy`). Autonomy is public, so this package is public. There is no extra package grant.

Maven packages have no "Manage Actions access" UI. That UI exists only for Container, npm, NuGet, and RubyGems packages.

## CI

CI already authenticates with `GITHUB_TOKEN` + `packages: read` via `actions/setup-java` (`server-id: github`). That is enough. Leave the workflow unchanged.

If a later consume job (Yuni #39) returns 401, then add a classic PAT with `read:packages` as a repo secret. Do not add that now.

## quarkus:dev

Do not put a token in git. Use `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_LOGIN</username>
      <password>A_PAT_WITH_read:packages</password>
    </server>
  </servers>
</settings>
```

Yuni #39 adds the dependency. This repo only names the consume path.
