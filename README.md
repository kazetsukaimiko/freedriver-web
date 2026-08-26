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
  - `grafana.freedriver.io` — 404 on purpose; Grafana is loopback-only
- Keycloak 26 + local Postgres 16
- Grafana + Loki + Prometheus + Alloy (see Observability)
- Mosquitto 2.1.2 MQTTS at `mqtt.freedriver.io:8883` (host 8883 only; no 1883). Connect notes: [docs/mqtt-connect.md](docs/mqtt-connect.md).

## Observability

Grafana and the Keycloak admin console are not on the public internet.

```
ssh -L 3000:127.0.0.1:3000 -L 8081:127.0.0.1:8081 -i <key> lonewatt-techops@138.197.90.42
```

Then Grafana is http://127.0.0.1:3000 (user `admin`, password `GF_SECURITY_ADMIN_PASSWORD` in `/opt/freedriver-secrets/.env`). Keycloak admin is http://127.0.0.1:8081/admin. Public `https://auth.freedriver.io/admin` returns 404; user login on that host is unchanged. `https://grafana.freedriver.io` returns 404.


Alloy tails Docker container logs into Loki (14 days). Prometheus keeps ~15 days and scrapes itself, Alloy, and `app:8080/q/metrics` (that target is down until the Quarkus app exports Micrometer metrics). Loki, Prometheus, and Alloy stay on the compose network. Grafana and Keycloak admin bind 127.0.0.1 only.

`grafana.freedriver.io` A record is Sysadmin, same as `auth.freedriver.io`.

## App

The product app lives in `app/`: Quarkus 3.38 (Java 21) with Quinoa serving a React TypeScript SPA. It talks normal REST under `/api`. Keycloak at `https://auth.freedriver.io` will handle auth later; OIDC is present as a dependency with commented config so the app starts without secrets.

```shell
cd app
./mvnw quarkus:dev
```

Requires Java 21. Quinoa can install Node for the UI build. Open http://localhost:8080 for the dashboard (`GET /api/hello` and `GET /api/build` are public). Production is `https://app.freedriver.io` via Caddy → the Compose `app` service.

## Appliances API

`GET/POST /api/appliances` is implemented against a **fake** autonomy for `quarkus:dev` and CI. The browser is REST only. Production keeps the route disabled (404), OIDC off, and MQTT disconnected. Integration contract: [`docs/appliances.md`](docs/appliances.md). Autonomy MQTT how-to: [`docs/autonomy-mqtt.md`](docs/autonomy-mqtt.md).

The live command route is **not** Done. It is blocked on [#25](https://github.com/kazetsukaimiko/freedriver-web/issues/25) and Security sign-off on [#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27).

## Deploy

Push or merge to `main`, or run the **Deploy** workflow. GitHub Actions rsyncs this repo to `/opt/freedriver-web` and runs `docker compose --env-file /opt/freedriver-secrets/.env up -d` so `${VAR}` interpolation reads the secrets file, not a `.env` in the git tree.

### Build number

A successful `main` deploy stamps `YEAR-MONTH_rBUILD_NUM` (UTC year-month + `github.run_number`), same scheme as autonomy mqtt-contract. Example: `2026-08_r45`. Not semver. Not `1.0.<run>`.

That string is:

1. Written to `BUILD_NUMBER` (rsynced; not committed) and passed as the Compose `app` build arg
2. Baked into the image: Maven `versions:set` in the Docker builder (git POM stays `1.0.0-SNAPSHOT`) plus `-Dquarkus.application.version`
3. Annotated-tagged on `GITHUB_SHA` **after** SSH deploy succeeds (not on PRs)
4. Exposed for UX [#49](https://github.com/kazetsukaimiko/freedriver-web/issues/49) at public `GET /api/build` → `{"build":"2026-08_r45"}`

Local `./mvnw` and Compose builds without `BUILD_NUMBER` report `1.0.0-SNAPSHOT`. Read the stamp from `/api/build` (or `quarkus.application.version`); do not put MQTT or port 8883 in webui.

Required repository secrets:

| Secret | Value |
| --- | --- |
| `DEPLOY_HOST` | `138.197.90.42` |
| `DEPLOY_USER` | `root` |
| `DEPLOY_SSH_KEY` | private key whose public half is in `root` `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan 138.197.90.42` |

Pull requests also get an advisory Grok (xAI) review (`.github/workflows/grok-review.yml`). **kaze must add repository secret `XAI_API_KEY` from [console.x.ai](https://console.x.ai)** — do not invent a key. Optional Actions variable `XAI_MODEL` (default `grok-4`). The system prompt is kaze’s lock from [#64](https://github.com/kazetsukaimiko/freedriver-web/issues/64), copied verbatim into `.github/grok-review-prompt.md`. The job checks out the PR head and sends full touched files plus one-level-out neighbors; it is not a hunk-only marketplace action. Comments only: the job does not fail on findings, is not a merge gate, and does not count as the required human review.

Do not commit keys. Mail DNS (Proton) is owned by Sysadmin; leave it alone. `auth.freedriver.io`, `grafana.freedriver.io`, and `mqtt.freedriver.io` A records are also Sysadmin.
