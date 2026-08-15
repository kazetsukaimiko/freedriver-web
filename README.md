# freedriver-web

Public site for [freedriver.io](https://freedriver.io). Caddy serves a static page and gets certificates itself. This repo is the proving-ground deploy; Lonewatt will copy the same pattern.

## Stack

- `docker-compose.yml` — Caddy 2 on 80/443
- `Caddyfile` — `freedriver.io` and `www.freedriver.io`
- `site/` — static files

The VPS host stays thin (SSH + Docker). Compose lives in `/opt/freedriver-web`.

## Deploy

Push or merge to `main`, or run the **Deploy** workflow. GitHub Actions rsyncs this repo to the VPS and runs `docker compose up -d`.

Required repository secrets:

| Secret | Value |
| --- | --- |
| `DEPLOY_HOST` | `138.197.90.42` |
| `DEPLOY_USER` | `root` |
| `DEPLOY_SSH_KEY` | private key whose public half is in `root` `authorized_keys` |
| `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan 138.197.90.42` |

Do not commit keys. Mail DNS (Proton) is owned by Sysadmin; leave it alone.
