# SMS OTP scaffold

House users sign in with a phone number and a code. Team accounts sign in with a password. This scaffold adds the `sms` compose service and the Keycloak authenticator that calls it. Number hand-out lives in portal-admin on `app.freedriver.io`.

Follow-ups: kaze replaces the stub with the Quarkus `sms` service and ships the portal-admin number hand-out in [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107). Sysadmin sets up the SMS vendor account in [#108](https://github.com/kazetsukaimiko/freedriver-web/issues/108); its credentials go on the `sms` service.

## sms service

Compose service `sms` listens on port 8080 on the compose network. Keycloak calls it at `http://sms:8080` with the shared secret in header `X-Freedriver-Sms-Secret`. A missing or wrong secret gets `401`.

| Call | Body | Response |
| --- | --- | --- |
| `POST /otp/send` | `{"phone":"+15555550100"}` | `200` `{"status":"sent"}` |
| `POST /otp/verify` | `{"phone":"+15555550100","code":"123456"}` | `200` `{"username":"<keycloak-username>"}`, or `400` `{"error":"invalid-code"}` |

The service enforces:

- Provisioned numbers only. It texts a code only to numbers that portal-admin provisioned, each mapped to one Keycloak username. Every well-formed number gets the same `200 {"status":"sent"}`, so the response is identical for unknown numbers.
- Code expiry. Codes are 6 digits, valid for 5 minutes, one pending code per number, and a code works once.
- Attempts per phone. 5 wrong codes per number in 15 minutes drop the pending code, and verify answers `invalid-code` for that number until the window ends. 5 sends per number in 15 minutes, after which send answers `429`. Both limits count every number the same way.

The stub in `sms/stub` implements these rules in `OtpService` and starts with an empty registry and an unset SMS sender, so `/health`, send and verify answer `503`. `/health/live` answers `200` while the server runs, and the compose healthcheck calls it. The #107 service keeps this HTTP contract, header and limits, and connects the portal-admin registry and the vendor.

Compose passes `SMS_OTP_SHARED_SECRET` from `/opt/freedriver-secrets/.env` to `sms` and `keycloak` through each service's `environment` as `${SMS_OTP_SHARED_SECRET:-}`. When the secret is empty or the placeholder, phone sign-in is off: the Keycloak step skips itself, the stub answers `401`, and the provisioning script leaves the realm flow unchanged.

The Twilio Verify credentials reach `sms` only, from `/opt/freedriver-secrets/twilio-verify.env` through a required `env_file` ([#170](https://github.com/kazetsukaimiko/freedriver-web/issues/170)). The variables are `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY_SID`, `TWILIO_API_KEY_SECRET` and `TWILIO_VERIFY_SERVICE_SID`. Sysadmin writes the file, `root:lonewatt-techops` mode 640. Placeholders: `secrets/twilio-verify.env.example`.

## Keycloak SPI

Source: `keycloak/sms-otp-spi`. Provider id: `freedriver-sms-otp`. The Keycloak image build copies the JAR to `/opt/keycloak/providers/freedriver-sms-otp.jar`, copies the `freedriver` login theme to `/opt/keycloak/themes/freedriver`, and runs `kc.sh build`. Requirement choices: ALTERNATIVE and DISABLED.

On the browser flow the password form is the first challenge. Phone sign-in is the last top-level Alternative, reached with "Try another way".

Pages render through `context.form()` with `freedriver-sms-phone.ftl` and `freedriver-sms-code.ftl` from `keycloak/themes/freedriver/login`, which extends `keycloak.v2`, so the phone pages share the password page's look and Keycloak's security headers. Strings live in the theme's `messages_en.properties`. Both pages carry a "Sign in with password" link that restarts the login on the password form.

Per auth session, kept in auth session notes:

- The code page reads "Enter the 6-digit code we texted to your phone." for every number.
- A wrong code keeps the code form with "That code didn't match. Try again." The 5th wrong code clears the pending code and returns to the phone form with "Too many tries. Enter your phone number to get a new code." while texts remain.
- 4 texts per auth session: the first code and 3 resends. Entering the number again draws on the same budget. Once the 4 texts are used, the code page shows "Code limit reached. Try again in 15 minutes." with "Sign in with password" as its only link. The phone form, including after a 5th wrong code, shows the same message and skips the send.

After sms verifies a code, the SPI signs in the Keycloak user named by `username` when both of these hold:

- the user is enabled and its `phone` attribute, normalized like the typed number (whitespace, dots, dashes and parentheses removed), equals the verified number;
- the user is a direct member of the top-level group `phone-sign-in`.

The SPI denies a user whose effective roles (direct, group and composite) include a realm or client role named `portal-admin` or any `realm-management` client role, and denies on any lookup error. Those privileged accounts sign in with the password flow and its MFA ([#27](https://github.com/kazetsukaimiko/freedriver-web/issues/27)).

## Techops steps

As root on the VPS:

1. Add `SMS_OTP_SHARED_SECRET` to `/opt/freedriver-secrets/.env`. Generate the value with `openssl rand -base64 32`.
2. Recreate both services so they pick it up:

```shell
cd /opt/freedriver-web
docker compose --env-file /opt/freedriver-secrets/.env up -d --build keycloak sms
```

3. Run `./scripts/provision-keycloak-sms-otp.sh`.

The script copies the built-in `browser` flow to `browser-freedriver` and adds `freedriver-sms-otp` as the last top-level ALTERNATIVE on the copy. It checks that `auth-username-password-form` stays REQUIRED inside the forms Alternative. It creates the `phone-sign-in` group, adds the user profile attribute `phone` with admin-only view and edit, and sets the realm login theme to `freedriver`. Once the Keycloak container has a real secret, it sets `browser-freedriver` as the realm browser flow.

Keycloak depends only on `keycloak-db`, so password login comes up whatever the `sms` health.

### kaze's phone user (#169)

1. Put kaze's number in international form in `/opt/freedriver-secrets/.env` as `FREEDRIVER_SEED_PHONE`. Only the `sms` service receives it; `sms` seeds it once into its phone list under the username `kaze` ([#168](https://github.com/kazetsukaimiko/freedriver-web/issues/168)).
2. Recreate `sms` with `docker compose --env-file /opt/freedriver-secrets/.env up -d sms`.
3. Run `./scripts/provision-keycloak-freedriver.sh` after `./scripts/provision-keycloak-sms-otp.sh`.

The script creates or updates the Keycloak user `kaze` with the `phone` attribute set to that number, direct membership in `phone-sign-in`, and the realm role `dashboard`. It stops with an error when `kaze` has another group or a role beyond `dashboard` and `default-roles-freedriver`, or when another user already has the number. It never prints the number. A second run changes nothing. With the setting empty, it leaves `kaze` as it is.
