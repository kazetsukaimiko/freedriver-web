# Consume freedriver-mqtt-contract

Coordinates: `io.freedriver:freedriver-mqtt-contract` (reactor module in this repo; published from `main` as `YEAR-MONTH_rBUILD_NUM`).

The portal owns the dialect. Topics are `freedriver/v1/{instanceId}/appliances|commands`. Autonomy is a leaf: it pins this artifact. Do not depend on `io.freedriver.autonomy:autonomy-mqtt-contract`. Do not vendor the records into `app/` or into autonomy.

Repository (for out-of-repo consumers): `https://maven.pkg.github.com/kazetsukaimiko/freedriver-web` (Maven repo id `github`).

Do not use `1.0.0-SNAPSHOT` from GitHub Packages. Local `./mvnw` builds the SNAPSHOT from the reactor.

## In this repo

`app` depends on `${project.version}` of `freedriver-mqtt-contract`. Change the records and the app in the same PR. Parse JSON in `ApplianceJson` (MQTT handler codec), never on the records.

## Autonomy

Pin a published `YEAR-MONTH_rBUILD_NUM` after `main` publishes. MQTT handler maps topic `{instanceId}` plus body. Body has no `instanceId`.

## CI

This repo no longer resolves the contract from autonomy's GitHub Packages. `./mvnw -B test` at the repo root builds the module then the app.
