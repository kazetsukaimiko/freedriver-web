# Consume autonomy-mqtt-contract

Coordinates: `io.freedriver.autonomy:autonomy-mqtt-contract:1.0.0-SNAPSHOT`

Repository: `https://maven.pkg.github.com/kazetsukaimiko/autonomy` (Maven repo id `github`).

## CI

`GITHUB_TOKEN` via `actions/setup-java` (`server-id: github`). Job needs `packages: read`.

After the first publish from kazetsukaimiko/autonomy, @kazetsukaimiko must grant this repo read access on the GitHub package (user-owned packages start private to the publishing repo).

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
