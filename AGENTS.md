# Agent rules

## Start from the remote integration branch

Fetch before any work. A local `master` or `main` is not current.

1. `git fetch origin`
2. `git fetch upstream` when that remote exists.
3. Branch from the first ref that exists, in this order:
   - `upstream/master`
   - `origin/master`
   - the remote HEAD (`origin/main` on this repo today)

Create the working branch from that fetched ref.

## Merge and deploy

kaze approves. The submitter merges.

After the merge, the submitter watches GitHub Actions until **Deploy** succeeds. A push to `main` runs that workflow. It stamps `YEAR-MONTH_r<run_number>` (UTC year-month plus `github.run_number`), rsyncs the repo to the VPS, and runs `docker compose up -d --build`. The job pushes an annotated tag of that stamp only after the SSH deploy succeeds.

## Confirm the running version

The dashboard image bakes that stamp into `quarkus.application.version`. Public `GET https://app.freedriver.io/api/build` returns it:

```json
{"build":"2026-10_r45"}
```

When Deploy succeeds, request that URL. `build` must equal the stamp from that run (`NEW_VERSION`, also the annotated tag). A previous stamp means freedriver.io is still serving the old dashboard image.

## Failed Actions

When GitHub Actions fails, the submitter debugs and proposes a fix. The proposal is a pull request. These rules apply to that pull request: start from the latest remote integration branch, kaze approves, the submitter merges, watches Deploy, and checks `GET /api/build`.
