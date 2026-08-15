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

## Deploy

Push or merge to `main`, or run the **Deploy** workflow. GitHub Actions rsyncs this repo to `/opt/freedriver-web` and runs `docker compose up -d`.

Required repository secrets:

| Secret | Value |
| --- | --- |
| `DEPLOY_HOST` | `138.197.90.42` |
| `DEPLOY_USER` | `root` |
| `DEPLOY_SSH_KEY` | private key whose public half is in `root` `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan 138.197.90.42` |

Do not commit keys. Mail DNS (Proton) is owned by Sysadmin; leave it alone. `auth.freedriver.io` A record is also Sysadmin.
