# Consume autonomy-mqtt-contract

Coordinates: `io.freedriver.autonomy:autonomy-mqtt-contract:2026-08_r51`

Repository: `https://maven.pkg.github.com/kazetsukaimiko/autonomy` (Maven repo id `github`).

`app/pom.xml` depends on that exact version. Do not use `1.0.0-SNAPSHOT`. Do not use `2026-08_r45`. Do not vendor the jar.

## CI

`GITHUB_TOKEN` via `actions/setup-java` (`server-id: github`). Job needs `packages: read`.

If resolve returns 401/403, @kazetsukaimiko still needs to grant this repo read on the package.

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
