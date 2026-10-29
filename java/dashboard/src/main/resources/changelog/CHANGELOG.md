# Changelog

## 2026-10_r141 (2026-10-29)


### Features

* also notify OpenBot on CI success ([e75102e](https://github.com/kazetsukaimiko/freedriver-web/commit/e75102e2251934aa4099b99efdd292935dd7b2ab))
* **ci:** notify OpenBot on failed CI/CD runs ([9068205](https://github.com/kazetsukaimiko/freedriver-web/commit/90682057ec0970c3a4af6ced13befb7304381bd5))
* **ci:** notify OpenBot on PR review and close ([a86be98](https://github.com/kazetsukaimiko/freedriver-web/commit/a86be9850796792fcbc085727a4996da0d1a9249))
* **dispatcher:** enable prod-only OIDC with issuer and azp locks ([3a96036](https://github.com/kazetsukaimiko/freedriver-web/commit/3a960362f01127b1bb45dd328944998fd1272893)), closes [#211](https://github.com/kazetsukaimiko/freedriver-web/issues/211)
* **keycloak:** provision one house as a Keycloak user ([4b7e57f](https://github.com/kazetsukaimiko/freedriver-web/commit/4b7e57f1fcaacb584cbf57a774417c764199af8f))
* **keycloak:** provision one house as a Keycloak user ([586f6d6](https://github.com/kazetsukaimiko/freedriver-web/commit/586f6d6d891a1c9e55b18e5bb313a9a6841caf69)), closes [#319](https://github.com/kazetsukaimiko/freedriver-web/issues/319)
* notify OpenBot on failed CI/CD runs ([129c3a3](https://github.com/kazetsukaimiko/freedriver-web/commit/129c3a3ea277f596e04b8529dbca7430492bb17e))


### Bug Fixes

* **ci:** post OpenBot only when the run failed ([d792d03](https://github.com/kazetsukaimiko/freedriver-web/commit/d792d03c5357e69698da13d2efd7bf11bbba257b)), closes [#338](https://github.com/kazetsukaimiko/freedriver-web/issues/338)
* **deploy:** persist dashboard live-commands env ([5fb534f](https://github.com/kazetsukaimiko/freedriver-web/commit/5fb534f0c8090aa6dea9846487ca244f7bc7e95f))
* **deploy:** persist dashboard live-commands env ([e749be3](https://github.com/kazetsukaimiko/freedriver-web/commit/e749be3c012f93f80237e67f8b0b5781bcee70f7)), closes [#291](https://github.com/kazetsukaimiko/freedriver-web/issues/291)
* **deploy:** stamp Release PRs as YEAR-MONTH_rN ([68181e3](https://github.com/kazetsukaimiko/freedriver-web/commit/68181e3629206d578ca18f718631a9fde7d9557c))
* **deploy:** stamp Release PRs as YEAR-MONTH_rN ([f1d5338](https://github.com/kazetsukaimiko/freedriver-web/commit/f1d53388bb0f2c019e98ab25827dbe94411f3683)), closes [#312](https://github.com/kazetsukaimiko/freedriver-web/issues/312)
* **keycloak:** pin the freedriver-dispatcher redirect to the grok.com callback ([e4e46b7](https://github.com/kazetsukaimiko/freedriver-web/commit/e4e46b76d3250e47e002b1026fb916423d0d760b))
* **keycloak:** return the created house from the interface ([6572032](https://github.com/kazetsukaimiko/freedriver-web/commit/657203238576811c5e2d1276da143e3b2d1bd8e7)), closes [#319](https://github.com/kazetsukaimiko/freedriver-web/issues/319)
* **keycloak:** stop passing a bootstrap admin username without a password ([441603a](https://github.com/kazetsukaimiko/freedriver-web/commit/441603a09f74cb368d01d5cde85434643b531332))
* **keycloak:** stop passing a bootstrap admin username without a password ([99f09b0](https://github.com/kazetsukaimiko/freedriver-web/commit/99f09b022c6f4d7900b16f0a9ffdeffe6dd12c61)), closes [#140](https://github.com/kazetsukaimiko/freedriver-web/issues/140)
