# Consume freedriver-mqtt-contract

Coordinates: `io.freedriver:freedriver-mqtt-contract` (reactor module in this repo; published from `main` as `YEAR-MONTH_rBUILD_NUM`).

The portal owns the dialect. Topics are `freedriver/v1/{instanceId}/appliances|commands`. Autonomy is a leaf: it pins this artifact. `app` and autonomy both take the records from this artifact as a Maven dependency.

Repository (for out-of-repo consumers): `https://maven.pkg.github.com/kazetsukaimiko/freedriver-web` (Maven repo id `github`).

`1.0.0-SNAPSHOT` is the local reactor build (`./mvnw`). GitHub Packages consumers pin a published version (see Autonomy).

## In this repo

`app` depends on `${project.version}` of `freedriver-mqtt-contract`. Change the records and the app in the same PR. JSON parsing lives in `ApplianceJson` (MQTT handler codec); the records are plain data.

## Autonomy

Pin a published `YEAR-MONTH_rBUILD_NUM` after `main` publishes. MQTT handler maps topic `{instanceId}` plus body; `instanceId` comes from the topic.

## CI

`./mvnw -B test` at the repo root builds the contract module from the reactor, then the app.
