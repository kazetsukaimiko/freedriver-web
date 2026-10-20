# Agent rules

## Start from the remote integration branch

Fetch before any work. Work from the fetched remote integration ref. A local `master` or `main` may be behind it.

1. `git fetch origin`
2. `git fetch upstream` when that remote exists.
3. Branch from the first ref that exists, in this order:
   - `upstream/master`
   - `origin/master`
   - the remote HEAD (`origin/main` on this repo today)

Create the working branch from that fetched ref.

## Opening PRs in this repo

Open the pull request as the GitHub user for your role. The default role is yuni (`kazetsukaiyuni`). A techops role uses `lonewatt-techops`. A security role uses `lonewatt-security`.

Open the pull request as a draft.

Watch the pull request until it merges. That watch covers review comments and CI.

CI has to pass. After each push, watch CI and fix failures with more commits on the same branch. Do that without waiting for another prompt.

When CI is green, read the automated review. [The Grok review on pull request 203](https://github.com/kazetsukaimiko/freedriver-web/pull/203#pullrequestreview-5579525971) is the shape of that comment.

Take that review seriously. Set a suggestion aside when earlier work already ruled the change out, or when the suggestion clashes with other feedback or a standing mandate.

Reply on the pull request for every comment you act on or set aside, automated or from a person. The reply says what changed, or asks the clarifying question. Post it as the GitHub user for your role.

When CI is green and the automated review is settled, mark the pull request ready for review and request a review from kaze (`kazetsukaimiko`).

Address kaze's feedback with commits on the same branch, and with that reply. When you want to push back or clarify a requirement, the reply is the place to do it, and then wait.

When kaze approves, merge only after the head-repository check below. Leave the pull request open when kaze's review says to hold the merge.

Before merging, read the pull request's `head.repo.full_name`.

- When `head.repo.full_name` is `kazetsukaimiko/freedriver-web`, follow the normal merge gate.
- When `head.repo.full_name` is anything else, including a fork or a missing head repository, take no action on that pull request. Do not merge it, push to it, or comment on it. Tell the Product Manager the pull request number and the head repository.

After the merge, follow [Merge and deploy](#merge-and-deploy).

## Merge and deploy

After the merge, watch GitHub Actions until the **Deploy** workflow succeeds. What that workflow does, and how the build stamp is formed, is in the README under [Deploy](README.md#deploy) and [Build number](README.md#build-number).

## Confirm the running version

When Deploy succeeds, `GET https://app.freedriver.io/api/build` returns the stamp from that run. The response shape, and where the dashboard image records the stamp, is in the README under [Build number](README.md#build-number). The live `build` value has to equal that run's stamp.

## Failed Actions

When GitHub Actions fails, debug and open the fix as a pull request under [Opening PRs in this repo](#opening-prs-in-this-repo). After that pull request merges, follow [Merge and deploy](#merge-and-deploy).
