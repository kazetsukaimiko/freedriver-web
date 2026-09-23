# SMS OTP scaffold

House users will sign in with a phone number and a code. Team accounts keep password. This card is the compose sibling and the Keycloak HTTP client only. The Quarkus service that actually sends SMS is kaze [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107). The vendor account is Sysadmin [#108](https://github.com/kazetsukaimiko/freedriver-web/issues/108).

`freedriver.appliances.live-commands` stays `false`. `quarkus.oidc.enabled` stays `false`. This is not [#40](https://github.com/kazetsukaimiko/freedriver-web/issues/40) and not [#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27). Phones are not MQTT client ids, topics, or ACL users.

## Problem

Keycloak has to ask something to send a code and to check it, without holding AWS SMS credentials in the Keycloak JVM. That something is a sibling service on the compose network, not a route on the public site.

## What is wired

Compose service `sms` listens on port 8080 of the Docker network. Keycloak calls `http://sms:8080`. There is no public hostname and no Caddy site. Number hand-out stays on `app.freedriver.io` portal-admin when [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107) ships it. Keycloak does not wait on `sms` health, so a down stub does not block password login.

The image in git is a stub. `GET /health` returns 503 until the real image is swapped. `POST /otp/send` and `POST /otp/verify` return 401 unless header `X-Freedriver-Sms-Secret` matches `SMS_OTP_SHARED_SECRET`. A matching secret still returns 503. The stub does not send a message and does not accept a code.

Shared secret, both sides:

| | |
| --- | --- |
| Env | `SMS_OTP_SHARED_SECRET` |
| Live file | `/opt/freedriver-secrets/.env` on the VPS only |
| Git | `secrets/sms-otp.env.example` |

That example is not loaded by Compose. Its value is `placeholder-not-a-live-secret`. The stub and the Keycloak SPI treat a missing value and that placeholder as no secret. Do not commit a real secret. Do not put AWS vendor credentials in the Keycloak environment. When [#108](https://github.com/kazetsukaimiko/freedriver-web/issues/108) lands, those credentials belong on the `sms` service only.

## Keycloak SPI

Source: `keycloak/sms-otp-spi`. Provider id: `freedriver-sms-otp`. The image build copies the JAR to `/opt/keycloak/providers/freedriver-sms-otp.jar` and runs `kc.sh build`. The requirement choices are ALTERNATIVE and DISABLED. REQUIRED is not offered.

The SPI reads `SMS_OTP_SHARED_SECRET` from the environment. It posts only to `http://sms:8080`:

| Call | Body | Success |
| --- | --- | --- |
| `POST /otp/send` | `{"phone":"+15555550100"}` | `200` `{"status":"sent"}` |
| `POST /otp/verify` | `{"phone":"+15555550100","code":"123456"}` | `200` `{"username":"<keycloak-username>"}` |

Header on both: `X-Freedriver-Sms-Secret`. Anything else, including the stub's 401 and 503, does not create a session. Verify sets the Keycloak user from `username`. It does not use the phone as the username. The phone is not written to MQTT.

On the browser flow the password form is still the first challenge. Phone sign-in is another Alternative ("Use password instead" / Try another way). If the secret is missing, the SPI reports itself as not configured and the attempt is skipped, so password still runs.

## Techops steps

After the stack is up, as root on the VPS:

1. Add a generated secret to `/opt/freedriver-secrets/.env`. Do not reuse the placeholder in `secrets/sms-otp.env.example`. One way to generate it: `openssl rand -base64 32`. Do not commit the result.
2. Recreate the two services so both see the env var:

```shell
cd /opt/freedriver-web
docker compose --env-file /opt/freedriver-secrets/.env up -d --build keycloak sms
```

3. Run `./scripts/provision-keycloak-sms-otp.sh`.

The script copies built-in flow `browser` to `browser-freedriver` (it does not edit `browser`). It adds `freedriver-sms-otp` as ALTERNATIVE on that copy. It checks that `auth-username-password-form` is still REQUIRED inside the forms subflow, and that the forms subflow is still an ALTERNATIVE. It refuses a DISABLED password execution and a REQUIRED SMS execution. It does not change the direct-grant flow. It does not print the secret.

It sets the realm browser flow to `browser-freedriver` only when the secret inside the Keycloak container is present and is not the placeholder. Otherwise it exits and leaves the current browser flow in place.

`sms` will look unhealthy while the stub answers 503 on `/health`. That is expected until the [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107) image replaces `sms/stub`.

## Follow-ups

- [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107) — kaze ships the Quarkus `sms` binary and the portal-admin number hand-out. Swap the stub image. Keep the HTTP contract and the shared-secret header. Still no public sms host.
- [#108](https://github.com/kazetsukaimiko/freedriver-web/issues/108) — Sysadmin vendor account. Credentials go to the `sms` service only, never into Keycloak or git.
- Backend acceptance tests come later. They are not this card.

`live-commands` stays false. Do not flip `quarkus.oidc.enabled` for this work.
