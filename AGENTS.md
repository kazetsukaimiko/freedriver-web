# Agent rules

## Pull requests

Follow the PR follow-through mandate:

https://github.com/kazetsukaimiko/ai-tools/blob/main/sdlc/pr-follow-through/MANDATE.md

That file is the loop. It covers syncing the remote integration branch before every push, CI on the current SHA, the comment census, replies on every review surface, and staying with the pull request until it merges. The sections below are the host facts that mandate asks this repo to name. They do not relax the mandate.

## Integration branch

Fetch before any work. Branch from the first ref that exists, in this order:

1. `upstream/master`
2. `origin/master`
3. the remote HEAD (`origin/main` on this repo today)

A local `master` or `main` is that ref only after the fetch has updated it.

## Who opens, who reviews, who merges

Open the pull request as the GitHub user for your role. The default role is yuni (`kazetsukaiyuni`). A techops role uses `lonewatt-techops`. A security role uses `lonewatt-security`. Post replies as that same user.

Open the pull request as a draft.

`pr-ready-when-green` marks it ready and requests kaze (`kazetsukaimiko`) when CI, Commitlint, and Grok review succeed on `main`. It leaves the pull request draft when the label is `keep-draft`, the base is not `main`, Grok's verdict on this head is `request-changes`, or Grok did not run.

When CI is green, read the automated review. [The Grok review on pull request 203](https://github.com/kazetsukaimiko/freedriver-web/pull/203#pullrequestreview-5579525971) is the shape of that comment. Settle it under the mandate.

When kaze approves, merge. Leave the pull request open when kaze's review says to hold the merge.

## Merge and deploy

Deploy starts when the Release pull request merges or when the Deploy workflow is run manually. After Deploy starts, watch GitHub Actions until it succeeds. What that workflow does, and how the build stamp is formed, is in the README under [Deploy](README.md#deploy) and [Build number](README.md#build-number).

## Confirm the running version

When Deploy succeeds, `GET https://app.freedriver.io/api/build` returns the stamp from that run. The response shape, and where the dashboard image records the stamp, is in the README under [Build number](README.md#build-number). The live `build` value has to equal that run's stamp.

## Failed Actions

When GitHub Actions fails, debug and open the fix as a pull request under [Pull requests](#pull-requests). After that pull request merges, follow [Merge and deploy](#merge-and-deploy). That post-merge check is part of the obligation for this repo.

## Engineering conventions

Each line names the mechanism this repo uses for that job.

- **HTTP between services.** The JAX-RS interface in a `-api` module is the contract. The server implements it. A caller injects a REST client built from that interface with `quarkus-rest-client-jackson`.
- **Keycloak administration.** `quarkus-keycloak-admin-rest-client` makes Admin API calls. `quarkus-oidc-client` obtains service-account tokens.
- **Mongo.** Panache entities and repositories hold persistence. Filters, sorts, limits, and atomic updates run in Mongo. The repository's module creates its indexes at startup.
- **MCP servers.** A tool is a `@Tool` method on `quarkus-mcp-server`. Bean Validation constraints on its arguments are the validation and the input schema. Security annotations carry its role checks.
- **MCP identity.** A tool call runs with the permissions of the user who connected.
- **Plain-Java modules.** `sms-client` runs inside Keycloak and `device-daemon` runs on user devices. Both use the JDK HTTP client and Jackson.

A pull request that hand-writes what a Quarkus extension provides says in its description what the extension lacks.
