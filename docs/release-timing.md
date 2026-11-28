# Release timing

Release [1.10.0](https://github.com/kazetsukaimiko/freedriver-web/pull/855) finished deploying **36 min 6 s** after the owner approved it. The release decision took 23 min 9 s of that, and machines worked for 8 min 43 s.

All times are EST (UTC−5). GitHub pages and logs show UTC. A duration is **measured** from timestamps unless it is marked **estimate**. The measurements cover releases 1.4.0 to 1.10.0 and were read from existing records on November 28, 2026 EST.

## 1.10.0 timeline

November 27, 2026 EST. Rows follow each other, and parallel jobs count once.

| Start (EST) | End (EST) | Duration | Kind | Interval | Source |
| --- | --- | --- | --- | --- | --- |
| 22:05:52 | 22:07:49 | 1 min 57 s | Waiting on a trigger | Approval, until TechOps asked for the release call | [Approval](https://github.com/kazetsukaimiko/freedriver-web/pull/855#pullrequestreview-6095330679), [TechOps record][record] |
| 22:07:49 | 22:30:58 | 23 min 9 s | Waiting on a person | Release decision | [TechOps record][record] |
| 22:30:58 | 22:31:56 | 58 s | Waiting on a trigger | TechOps rechecked the pull request, then closed it | [Close event](https://github.com/kazetsukaimiko/freedriver-web/pull/855#event-37527745180) |
| 22:31:56 | 22:32:02 | 6 s | Waiting on a trigger | Reopen at 22:31:58, until the checks started | [Reopen event](https://github.com/kazetsukaimiko/freedriver-web/pull/855#event-37527746370) |
| 22:32:02 | 22:35:25 | 3 min 23 s | Machine | Required checks. `test` was the longest, with 2 min 26 s in its Tests step | [`test` job](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103306426/job/140850389642) |
| 22:35:25 | 22:35:45 | 20 s | Waiting on a trigger | Checks passed, until the first merge attempt | TechOps session log (see [gaps](#gaps)) |
| 22:35:45 | 22:36:30 | 45 s | Retry | The first merge was refused for a missing code-owner review. TechOps read the release changes, approved at 22:36:25 and merged | [Code-owner approval](https://github.com/kazetsukaimiko/freedriver-web/pull/855#pullrequestreview-6095478694), [merge](https://github.com/kazetsukaimiko/freedriver-web/pull/855#event-37527924286) |
| 22:36:30 | 22:36:34 | 4 s | Waiting on a queue | Deploy run created at 22:36:32, until its gate job started | [Deploy run](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/attempts/1) |
| 22:36:34 | 22:36:37 | 3 s | Machine | Gate job. The [squash message](https://github.com/kazetsukaimiko/freedriver-web/commit/1cd7314e9923e88459341f14602952f2e169858e) held the release branch name | [Gate job](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851540895) |
| 22:36:37 | 22:36:41 | 4 s | Waiting on a queue | Deploy job queued | [Deploy job](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556) |
| 22:36:41 | 22:41:58 | 5 min 17 s | Machine | Deploy job, split below | [Deploy job](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556) |

| Kind | Total |
| --- | --- |
| Waiting on a person | 23 min 9 s |
| Machine | 8 min 43 s |
| Waiting on a trigger or queue | 3 min 29 s |
| Retry | 45 s |

The Deploy job in steps:

| Step | Start (EST) | Duration |
| --- | --- | --- |
| Runner setup and checkout | 22:36:41 | 6 s |
| [Build images](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556#step:6:1) | 22:36:47 | 3 min 59 s |
| [Push images](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556#step:7:1) | 22:40:46 | 29 s |
| [Pull on production](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556#step:9:1), including the file copy | 22:41:15 | 11 s |
| [Sync and start](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556#step:10:1) | 22:41:26 | 29 s |
| Tag and cleanup | 22:41:55 | 3 s |

The `production` environment has a branch policy only, (read on November 28, 2026 EST), so it adds zero approval or timer wait. All five Deploy runs measured here ran to completion: concurrency cancellation affected none of them.

### Live observations

Machine time ends when the Deploy job completes. These are TechOps [observations][record], made by hand around and after that point:

- 22:41:44 to 22:42:10 EST: the house's broker connection was refused during the sign-in restart. It reconnected at 22:42:38 EST, 54 s after the first refusal.
- 22:42:18 EST: the build endpoint gave no answer.
- 22:42:39 EST: the build endpoint had reported 1.10.0. The release was verified live 36 min 47 s after approval.

## Release approval is the release call

The owner's GitHub approval on a release pull request is now the release call. The release ships once the required checks pass. This rule was set on the night of 1.10.0, so the 23 min 9 s wait for a separate call belongs to the old process.

## Reported timings, checked

| Reported | Records show | Result |
| --- | --- | --- |
| [1.9.0 checks](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/45735983072): `test` 3.7 min, 2.5 min in Tests | 221 s, 150 s in Tests | Confirmed |
| `prod-image` 1.5 min, every other job at most 0.3 min | 92 s; longest other check 17 s | Confirmed |
| Deploy 1.9.0 attempt 2 took 7.2 min | Deploy job 7 min 13 s; whole attempt 7 min 27 s | Confirmed for the job |
| Deploy 1.8.0 took 7.5 min | Deploy job 7 min 33 s (7.6 min); whole run 7 min 43 s (7.7 min) | Corrected |
| Deploy 1.7.0 and 1.6.0, whole runs 8.3 and 7.5 min | 8 min 17 s and 7 min 32 s | Confirmed |
| Image build 5.3–5.5 min (1.8.0 and 1.9.0) | 5 min 21 s and 5 min 31 s | Confirmed; the five releases range 3 min 59 s to 6 min 34 s |
| Push 0.5–0.6 min | 37 s and 32 s | Confirmed; range 29–40 s |
| Production pull 0.6–0.9 min | 57 s and 34 s | Corrected to 0.6–1.0 min; range 11–57 s |
| Sync and start about 0.5 min | 32 s and 30 s | Confirmed; range 27–32 s |
| Machine time 11–12 min | 8 min 40 s to 16 min 10 s (`test` job plus Deploy job) | Corrected |

| Release | `test` job | Image build | Push | Pull | Sync and start | Deploy job | Deploy run |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [1.6.0](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43887360449) | 4:35 | 5:55 | 0:39 | 0:14 | 0:27 | 7:23 | 7:32 |
| [1.7.0](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/44213900378) | 8:07 | 6:34 | 0:40 | 0:16 | 0:27 | 8:03 | 8:17 |
| [1.8.0](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/44935447754) | 5:54 | 5:21 | 0:37 | 0:57 | 0:32 | 7:33 | 7:43 |
| [1.9.0, attempt 2](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/45737224483/attempts/2) | 3:41 | 5:31 | 0:32 | 0:34 | 0:30 | 7:13 | 7:27 |
| [1.10.0](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059) | 3:23 | 3:59 | 0:29 | 0:11 | 0:29 | 5:17 | 5:27 |

Durations are minutes:seconds. Check jobs started 1–3 s after they were queued in the runs read, and the 1.10.0 Deploy job waited 4 s.

## Why the checks did not start on the release pull request

1. The [Release workflow](https://github.com/kazetsukaimiko/freedriver-web/blob/1cd7314e9923e88459341f14602952f2e169858e/.github/workflows/release-please.yml) opens and updates the release pull request with the repository's `GITHUB_TOKEN`.
2. GitHub [creates no workflow run for most events made with that token](https://docs.github.com/en/actions/concepts/security/github_token). The exception is `pull_request` (opened, synchronize, reopened), whose runs wait for approval. TechOps saw `action_required` on the CI, Commitlint, Docs site and Writing policy runs of 1.10.0 before the reopen. The [CI run](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46058597529) and the Commitlint run hold zero jobs, and today they read `failure`, updated one second after the close.
3. The Grok review (`Principled Engineering Review`, `Security Review`) and `api-split` workflows listen on `pull_request_target`. The documented exception names `pull_request` only, and the records show zero runs of these workflows on the release head until the reopen. That the general rule is the cause is an inference from those two facts.
4. TechOps reopened the pull request with its own login. An event from that login starts every listed workflow, and all of them ran.

## Empty commit compared with close and reopen

| Method | CI and Commitlint | Review checks and `api-split` | Existing approval | Start to last required check |
| --- | --- | --- | --- | --- |
| Approve the waiting runs | Start | Stay absent | Kept in [1.7.0](https://github.com/kazetsukaimiko/freedriver-web/pull/553) | 1.5.1: [3 min 8 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43375478132/attempts/2). 1.5.2: [4 min 29 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43551071407/attempts/2). 1.7.0: [6 min 37 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/44210970808/attempts/2) |
| Empty commit | Start | Start | No recorded case with a live approval | 1.4.0: [3 min 32 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43211414522). 1.6.0: [4 min 40 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43885741685). 1.8.0: [5 min 58 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/44931480446). 1.9.0: [3 min 46 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/45735983072) |
| Close and reopen | Start | Start | Kept in [1.5.1](https://github.com/kazetsukaimiko/freedriver-web/pull/468), [1.7.0](https://github.com/kazetsukaimiko/freedriver-web/pull/553) and [1.10.0](https://github.com/kazetsukaimiko/freedriver-web/pull/855) | 1.5.1: [3 min 6 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43376284059). 1.5.2: [2 min 46 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/43554916834). 1.7.0: [3 min 50 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/44212031108) and [8 min 11 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/44212783892). 1.10.0: [3 min 27 s](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103306426) |

- An empty commit and a reopen start the same workflows: they raise the `synchronize` and `reopened` events, which every required check listens for. `api-split` first appears on 1.9.0 among these releases.
- The time to green follows the `test` job, so the two methods take about the same time.
- GitHub [documents stale-approval dismissal](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets) for pushes that change the pull request's diff. An empty commit leaves the diff as it was, so it is expected to keep an approval. This is untested here. The dismissals in the recorded release pull requests that carried an empty commit happened at earlier release updates that rewrote the branch.

### An automatic trigger

Give the Release workflow a GitHub App installation token in place of `GITHUB_TOKEN`. GitHub [documents](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow) that pull requests made with an app token run `pull_request` workflows without the approval step; `pull_request_target` runs are expected to follow, and a trial has to confirm it. The required checks and the approval rules stay as they are. Conditions:

- The app is installed on this repository only, with the contents, pull request and issue permissions the release action needs. Its key is readable only by the Release workflow on `main`.
- Every release pull request update then runs all checks, including both paid reviews. The branch was rewritten 9 times for 1.10.0, 17 times for 1.9.0 and 46 times for 1.8.0.
- A release update that changes the diff still dismisses an earlier approval, so the owner approves the final head.
- Tags made with the app token start tag workflows. Today's only one, SMS publishing, matches `sms/X.Y.Z` tags, which differ from release tags.
- From December 2, 2026, GitHub [blocks `pull_request_target` in public repositories](https://github.blog/changelog/2026-11-02-pull_request_target-blocked-by-default-december-2/) unless an Actions policy allows it. The review checks and `api-split` need that policy under every method on this page.

## The skipped Deploy on 1.9.0

November 25, 2026 EST. Tracked in [#818](https://github.com/kazetsukaimiko/freedriver-web/issues/818).

| Time (EST) | Event |
| --- | --- |
| 20:39:26 | Merge. The [squash message](https://github.com/kazetsukaimiko/freedriver-web/commit/f8eed70f) lacked the release branch name |
| 20:39:31 | [Gate job](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/45737224483/attempts/1) started, 5 s after the merge |
| 20:39:33 | Gate logged `deploy output is false`, 7 s after the merge |
| 20:39:36 | Attempt 1 ended with Deploy skipped |
| 20:40:01 | [Attempt 2](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/45737224483/attempts/2) started, 25 s later |
| 20:40:07 | Gate passed |
| 20:40:14 | Deploy job started, 48 s after the merge (11 s on 1.10.0) |
| 20:47:27 | Deploy job succeeded, 7 min 51 s after attempt 1 ended |

- The retry cost 37 s compared with 1.10.0.
- The logs leave the lookup's answer unknown: the gate prints its verdict only. The step succeeded under `pipefail`, so the lookup call exited cleanly and returned no matching branch.
- A delayed GitHub association is **supported but unproven**. The same commit and message got "false" at 20:39:33 EST and "true" at 20:40:07 EST, and the lookup lists [#732](https://github.com/kazetsukaimiko/freedriver-web/pull/732) for that commit today.
- The squash messages of 1.6.0, 1.7.0 and 1.8.0 also lacked the branch name, and their first lookup matched.

## Can Deploy ship an image a check already built?

No, as the workflows stand. The deployable saving from reuse is **zero**.

| | Pull request checks on 1.10.0 | Deploy of 1.10.0 |
| --- | --- | --- |
| Commit built | Merge ref `71647eb` (release head into `main`) | Squash commit `1cd7314` |
| Source tree | `15bed683` | `15bed683` |
| Build inputs | `BUILD_NUMBER=ci` for the dashboard, built with `--no-cache` | `BUILD_NUMBER=1.10.0` and `SOURCE_COMMIT` set to the squash commit, plus a source label |
| Images | 4 of 8 | 8 |
| Platform | Runner default; the logs name none | `linux/amd64` in the registry |
| Registry digest | None: images stay on the runner | One per image |

| Image | Built by a pull request check | Deploy 1.10.0 `linux/amd64` manifest | Check manifest list |
| --- | --- | --- | --- |
| `agent-dispatcher` | — | `c6b9deec8ecd` | — |
| `autonomy-mcp` | — | `fc167449f133` | — |
| `dashboard` | `prod-image` | `c78c7d42f19d` | `a62d70c2af3b` |
| `docker-logs-proxy` | `test` | `d89fcdfc8030` | `819e19b631a4` |
| `keycloak` | — | `87edd269d2fd` | — |
| `mosquitto` | `prod-image` | `d435808b9c9f` | `63cda64eabac` |
| `mosquitto-cert-sync` | `prod-image` | `8aaa3be4e385` | `6171b9699132` |
| `sms` | — | `2d57df11b4b2` | — |

Sources: [`prod-image` job](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103306426/job/140850389692), [Deploy build](https://github.com/kazetsukaimiko/freedriver-web/actions/runs/46103675059/job/140851558556#step:6:1). Check digests are manifest lists and Deploy digests are platform manifests, so the table shows what each run produced. Check images stay on the runner, so the registry holds nothing to promote. The release commit also comes into existence at the merge, after every pull request check. Reuse would need an artifact built for the release commit with the release inputs by a trusted job, which is the build Deploy already runs.

### Build steps that can be shortened

Deploy builds the images one after another, from a runner that starts empty.

| Image (seconds) | 1.6.0 | 1.7.0 | 1.8.0 | 1.9.0 | 1.10.0 |
| --- | --- | --- | --- | --- | --- |
| `agent-dispatcher` | 69 | 85 | 60 | 65 | 49 |
| `autonomy-mcp` | 53 | 66 | 53 | 55 | 38 |
| `dashboard` | 74 | 94 | 74 | 75 | 53 |
| `docker-logs-proxy` | 6 | 5 | 4 | 5 | 3 |
| `keycloak` | 87 | 72 | 66 | 66 | 48 |
| `mosquitto` | 16 | 15 | 11 | 13 | 7 |
| `mosquitto-cert-sync` | 2 | 1 | 1 | 2 | 1 |
| `sms` | 49 | 56 | 51 | 51 | 37 |

The first image of a run includes Compose start-up. In the four Java application images, Maven steps took 160 s on 1.10.0, and a dependency download was in progress for 83 s of that. Across the five releases the download figure is 83–189 s. The Keycloak build downloads too; its log format leaves that time unmeasured.

## Proposals, ordered by saving

Savings overlap where noted, so they do not add up directly.

| # | Proposal | Saving | Evidence | Conditions for safety |
| --- | --- | --- | --- | --- |
| 1 | Approval is the release call (**adopted**) | 23 min 9 s on the 1.10.0 shape | Measured wait from request to call | The owner's approval is on the current head; required checks pass |
| 2 | Checks start automatically on the release pull request, so they are green before approval | About 4 min 30 s (**estimate** from 1.10.0: 4 min 27 s from call to green checks) | Timeline rows 3–5; checks alone took 2 min 46 s to 8 min 11 s | See [an automatic trigger](#an-automatic-trigger) |
| 3 | Build and push each image in its own job | About 3 min (**estimate**) | Build step minus its slowest image: 3 min 6 s on 1.10.0, up to 5 min on earlier releases; plus about 25 s of sequential push; less about 30 s for added job queue, setup, checkout and base image pull | Every build and push succeeds before the Deploy job contacts production; images come from the release commit with today's inputs; only the build jobs get package write access; the Deploy job keeps the `production` environment |
| 4 | Keep Maven downloads between release builds | Alone, up to 1 min 23 s on 1.10.0; on top of proposal 3, about 5 s (**estimate**) | Download time in progress, above. With parallel builds the Keycloak image (48 s, download time unmeasured) sets the pace. Cache restore time is unmeasured | Only trusted runs on `main` write the cache; pull request runs never write it; credentials stay out of it |
| 5 | TechOps submits the code-owner review while checks run, then merges when they pass | About 1 min (**estimate**) | 65 s from last check to merge on 1.10.0, 45 s of it the refused merge | The review stays required; the merge pins the reviewed head; the squash message keeps the release branch name |
| 6 | Make the release gate retry its lookup ([#818](https://github.com/kazetsukaimiko/freedriver-web/issues/818)) | 37 s when it happens (1 of 5 releases, 1.6.0–1.10.0) | 1.9.0 table | Ordinary merges still skip Deploy; a failed lookup is visible |
| 7 | Push images in parallel (only if proposal 3 is not taken) | About 25 s (**estimate**) | Eight pushes of 2.9–3.8 s ran in turn on 1.10.0 | All pushes succeed before production is contacted |
| 8 | Pull unchanged third-party images less often | At most about 5 s | The last third-party image finished 5 s after the last release image on 1.8.0, 3 s on 1.9.0, and before it on the other three releases | Floating tags still receive security updates |
| — | Reuse a pull request image | Zero | [Comparison](#can-deploy-ship-an-image-a-check-already-built) | A trusted digest for the release commit, with required inputs, platforms, approval, checks and production gates |

Applied together to 1.10.0, proposals 1, 2, 3 and 5 would put approval to Deploy completion at about 4 to 5 minutes (**estimate**), against 36 min 6 s measured. About 2 minutes of that is the hand-off after approval, which has one measurement.

## Gaps

| Gap | Record that would close it |
| --- | --- |
| 22:05:52–22:07:49 EST: notification and preparation cannot be separated | A timestamp for when the approval reached TechOps |
| 22:07:49–22:30:58 EST: the request and the call have no GitHub event | The approval itself now serves as the call |
| 22:35:25–22:36:25 EST: the refused merge is only in the TechOps session log | A comment or event at the refusal |
| The 1.10.0 runs no longer show `action_required` | A recorded run state before the reopen |
| 1.9.0 gate: the lookup's answer | The gate printing what the lookup returned |
| Check build platform | The job printing its platform |
| Parallel build contention, cache restore time, Keycloak download time | A trial run, which needs separate authorization |
| Empty commit against a live approval | A trial on a test repository |

[record]: https://github.com/kazetsukaimiko/freedriver-web/issues/872#issuecomment-7240050902
