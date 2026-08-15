# Freedriver web UI

React + TypeScript SPA built with Vite and served by Quarkus Quinoa.

Do not run this folder on its own for normal development. From `app/`:

```shell
./mvnw quarkus:dev
```

Quarkus proxies the Vite dev server and serves `/api`.
