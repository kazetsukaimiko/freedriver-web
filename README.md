# freedriver-web

Public site for [freedriver.io](https://freedriver.io). Caddy terminates TLS and reverse-proxies apps. This repo is the proving-ground deploy; Lonewatt will copy the same pattern.

## VPS layout

```
/opt/freedriver-web/       this repo (rsync on deploy)
/opt/freedriver-secrets/   .env and other secrets (not in git)
/opt/freedriver-storage/   Docker bind mounts (Caddy certs, Postgres)
```

The host stays thin (SSH + Docker).

## Stack

- Caddy 2 on 80/443
  - `freedriver.io` / `www` — static `site/`
  - `auth.freedriver.io` — Keycloak 26
- Keycloak 26 + local Postgres 16

## App

The product app lives in `app/`: Quarkus 3.38 (Java 21) with Quinoa serving a React TypeScript SPA. It talks normal REST under `/api`. Keycloak at `https://auth.freedriver.io` will handle auth later; OIDC is present as a dependency with commented config so the app starts without secrets.

```shell
cd app
./mvnw quarkus:dev
```

Requires Java 21. Quinoa can install Node for the UI build. Open http://localhost:8080 for the dashboard (`GET /api/hello` is public). Compose, Caddy, and Keycloak stay with Techops — this app is not wired into `docker-compose.yml` yet.

## Deploy

Push or merge to `main`, or run the **Deploy** workflow. GitHub Actions rsyncs this repo to `/opt/freedriver-web` and runs `docker compose --env-file /opt/freedriver-secrets/.env up -d` so `${VAR}` interpolation reads the secrets file, not a `.env` in the git tree.

Required repository secrets:

| Secret | Value |
| --- | --- |
| `DEPLOY_HOST` | `138.197.90.42` |
| `DEPLOY_USER` | `root` |
| `DEPLOY_SSH_KEY` | private key whose public half is in `root` `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan 138.197.90.42` |

Do not commit keys. Mail DNS (Proton) is owned by Sysadmin; leave it alone. `auth.freedriver.io` A record is also Sysadmin.
