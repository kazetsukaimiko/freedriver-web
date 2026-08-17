# freedriver-web

Public site for [freedriver.io](https://freedriver.io). Caddy terminates TLS and reverse-proxies apps. This repo is the proving-ground deploy; Lonewatt will copy the same pattern.

## VPS layout

```
/opt/freedriver-web/       this repo (rsync on deploy)
/opt/freedriver-secrets/   .env and other secrets (not in git)
/opt/freedriver-storage/   Docker bind mounts (Caddy certs, Postgres, Grafana, Loki, Prometheus, Mosquitto)
```

The host stays thin (SSH + Docker). Deploy creates `/opt/freedriver-storage/{grafana,loki,prometheus,mosquitto}` if they are missing.

## Stack

- Caddy 2 on 80/443
  - `freedriver.io` / `www` — 308 to `app.freedriver.io`
  - `auth.freedriver.io` — Keycloak 26
  - `app.freedriver.io` — Quinoa app behind Caddy
  - `grafana.freedriver.io` — Grafana (internal only; Loki/Prometheus/Alloy are not published)
- Keycloak 26 + local Postgres 16
- Grafana + Loki + Prometheus + Alloy (see Observability)
- Mosquitto 2.1.2 MQTTS at `mqtt.freedriver.io:8883` (host 8883 only; no 1883). Connect notes: [docs/mqtt-connect.md](docs/mqtt-connect.md).

## Observability

https://grafana.freedriver.io — local admin user `admin`. The password is `GF_SECURITY_ADMIN_PASSWORD` in `/opt/freedriver-secrets/.env` (not in git). Keycloak SSO can wait.

Alloy tails Docker container logs into Loki (14 days). Prometheus keeps ~15 days and scrapes itself, Alloy, and `app:8080/q/metrics` (that target is down until the Quarkus app exports Micrometer metrics). Grafana, Loki, Prometheus, and Alloy listen on the compose network only; Caddy is the HTTPS front door.

`grafana.freedriver.io` A record is Sysadmin, same as `auth.freedriver.io`.

## App

The product app lives in `app/`: Quarkus 3.38 (Java 21) with Quinoa serving a React TypeScript SPA. It talks normal REST under `/api`. Keycloak at `https://auth.freedriver.io` will handle auth later; OIDC is present as a dependency with commented config so the app starts without secrets.

```shell
cd app
./mvnw quarkus:dev
```

Requires Java 21. Quinoa can install Node for the UI build. Open http://localhost:8080 for the dashboard (`GET /api/hello` is public). Production is `https://app.freedriver.io` via Caddy → the Compose `app` service.

## Deploy

Push or merge to `main`, or run the **Deploy** workflow. GitHub Actions rsyncs this repo to `/opt/freedriver-web` and runs `docker compose --env-file /opt/freedriver-secrets/.env up -d` so `${VAR}` interpolation reads the secrets file, not a `.env` in the git tree.

Required repository secrets:

| Secret | Value |
| --- | --- |
| `DEPLOY_HOST` | `138.197.90.42` |
| `DEPLOY_USER` | `root` |
| `DEPLOY_SSH_KEY` | private key whose public half is in `root` `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan 138.197.90.42` |

Do not commit keys. Mail DNS (Proton) is owned by Sysadmin; leave it alone. `auth.freedriver.io`, `grafana.freedriver.io`, and `mqtt.freedriver.io` A records are also Sysadmin.
