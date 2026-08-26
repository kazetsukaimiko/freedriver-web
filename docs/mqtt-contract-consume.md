# Consume autonomy-mqtt-contract

Coordinates: `io.freedriver.autonomy:autonomy-mqtt-contract:2026-08_r51`

Repository: `https://maven.pkg.github.com/kazetsukaimiko/autonomy` (Maven repo id `github`).

Published package: https://github.com/kazetsukaimiko/autonomy/packages/3213073 (`io.freedriver.autonomy.autonomy-mqtt-contract`).

`app/pom.xml` depends on that exact version. Do not use `1.0.0-SNAPSHOT`. Do not use `2026-08_r45`. Do not vendor the jar.

GitHub Maven packages are repository-scoped. They inherit permissions from the publishing repo (`kazetsukaimiko/autonomy`). Autonomy is public, so this package is public. There is no extra package grant.

Maven packages have no Manage Actions access UI.

## CI

CI already authenticates with `GITHUB_TOKEN` + `packages: read` via `actions/setup-java` (`server-id: github`). That is enough. Leave the workflow unchanged.

If resolve returns 401/403, use a classic PAT with `read:packages` (repo secret or `~/.m2/settings.xml`). Do not add that unless a job actually fails.

## VPS deploy

Same job-scoped `GITHUB_TOKEN` (not a PAT). `deploy.yml` writes gitignored `app/settings.xml` (`server` id `github`, username `github.actor`) and rsyncs it for `Dockerfile.compose` `mvn` only. The runtime image does not COPY that file. The VPS deletes it after `docker compose --build`. Do not put a token in git or a Docker ARG/ENV.

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
