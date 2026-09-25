# SMS OTP scaffold

House users sign in with a phone number and a code. Team accounts sign in with a password. This scaffold adds the `sms` compose service and the Keycloak authenticator that calls it. Number hand-out lives in portal-admin on `app.freedriver.io`.

Follow-ups: kaze replaces the stub with the Quarkus `sms` service and ships the portal-admin number hand-out in [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107). Sysadmin sets up the SMS vendor account in [#108](https://github.com/kazetsukaimiko/freedriver-web/issues/108); its credentials go on the `sms` service.

## sms service

Compose service `sms` listens on port 8080 on the compose network. Keycloak calls it at `http://sms:8080`.

The stub in `sms/stub`:

- `GET /health` returns 503.
- `POST /otp/send` and `POST /otp/verify` return 401 unless header `X-Freedriver-Sms-Secret` matches `SMS_OTP_SHARED_SECRET`, and 503 when it matches.

The #107 service keeps this HTTP contract and header.

`sms` and Keycloak both read `SMS_OTP_SHARED_SECRET` from `/opt/freedriver-secrets/.env` on the VPS.

## Keycloak SPI

Source: `keycloak/sms-otp-spi`. Provider id: `freedriver-sms-otp`. The Keycloak image build copies the JAR to `/opt/keycloak/providers/freedriver-sms-otp.jar` and runs `kc.sh build`. Requirement choices: ALTERNATIVE and DISABLED.

The SPI posts to `http://sms:8080` with the shared secret in `X-Freedriver-Sms-Secret`:

| Call | Body | Success |
| --- | --- | --- |
| `POST /otp/send` | `{"phone":"+15555550100"}` | `200` `{"status":"sent"}` |
| `POST /otp/verify` | `{"phone":"+15555550100","code":"123456"}` | `200` `{"username":"<keycloak-username>"}` |

Sign-in completes only on these 200 responses. Verify signs in the Keycloak user named by `username`.

On the browser flow the password form is the first challenge. Phone sign-in is a second Alternative, reached with "Try another way". When the secret is empty or the placeholder, the SPI marks the attempt as skipped and the flow continues to password.

## Techops steps

As root on the VPS, after the stack is up:

1. Add `SMS_OTP_SHARED_SECRET` to `/opt/freedriver-secrets/.env`. Generate the value with `openssl rand -base64 32`.
2. Recreate both services so they pick it up:

```shell
cd /opt/freedriver-web
docker compose --env-file /opt/freedriver-secrets/.env up -d --build keycloak sms
```

3. Run `./scripts/provision-keycloak-sms-otp.sh`.

The script copies the built-in `browser` flow to `browser-freedriver` and adds `freedriver-sms-otp` as an ALTERNATIVE on the copy. It checks that `auth-username-password-form` stays REQUIRED inside the forms Alternative. Once the Keycloak container has a real secret, it sets `browser-freedriver` as the realm browser flow. Otherwise it exits 1 and the realm keeps its current flow.

`sms` reports unhealthy while the stub answers 503 on `/health`. Keycloak depends only on `keycloak-db`, so password login comes up either way.
