# SMS OTP scaffold

House users sign in with a phone number and a code. Team accounts sign in with a password. This scaffold adds the `sms` compose service and the Keycloak authenticator that calls it. Number hand-out lives in portal-admin on `app.freedriver.io`.

Follow-ups: kaze replaces the stub with the Quarkus `sms` service and ships the portal-admin number hand-out in [#107](https://github.com/kazetsukaimiko/freedriver-web/issues/107). Sysadmin sets up the SMS vendor account in [#108](https://github.com/kazetsukaimiko/freedriver-web/issues/108); its credentials go on the `sms` service.

## sms service

Compose service `sms` listens on port 8080 on the compose network. Keycloak calls it at `http://sms:8080` with the shared secret in header `X-Freedriver-Sms-Secret`. A missing or wrong secret gets `401`.

The Quarkus service in `sms/` answers these calls (#107):

| Call | Body | Response |
| --- | --- | --- |
| `POST /sms/send` | `{"type":"otp","phone":"+15555550100"}` | `200` `{"sent":{"type":"otp","phone":"+15555550100"}}` |
| `POST /sms/verify` | `{"phone":"+15555550100","code":"123456"}` | `200` `{"verified":{"type":"otp","phone":"+15555550100"}}`, or `400` `{"error":"invalid-code"}` |

`type` selects the kind of text; `otp` (a sign-in code) is the one kind. The `phone` in each answer is the number from the request.

On the seeded number's first sign-in, the send body also carries the agreement wording the sign-in page showed under the number field: `{"type":"otp","phone":"+15555550100","agreement":{"wording":"By tapping Send code, you agree to receive a one-time sign-in code by text from Freedriver. Message and data rates may apply. Reply STOP to opt out."}}`. The wording must match that text exactly. Other answers:

- `400` `{"error":"bad-request"}` for a body that is not valid JSON, has a missing or unknown `type`, has a number that is not `+1` followed by ten digits, or carries other agreement wording.
- `400` `{"error":"invalid-code"}` for every rejected verify: an unlisted number, no pending code, an expired code or a wrong code all get this same answer.
- `429` `{"error":"rate-limited"}` with `Retry-After: 900` past a per-number limit, the same for every number.
- `503` `{"error":"unavailable"}` when Twilio cannot check a code.

The service enforces:

- Provisioned numbers only. It texts a code only to numbers that portal-admin provisioned, each mapped to one Keycloak username. Every well-formed number gets the same `sent` answer, so the response is identical for unknown numbers.
- Code expiry. Codes are 6 digits, valid for 5 minutes, one pending code per number, and a code works once.
- Attempts per phone. 5 wrong codes per number in 15 minutes drop the pending code, and verify then answers `429` for that number until the window ends. 5 sends per number in 15 minutes, after which send answers `429`. Both limits count every attempt, listed or unlisted, and answer every number the same way.

The fail-closed stub in `sms/stub` serves the earlier `/otp/send` and `/otp/verify` contract that the current Keycloak SPI calls. Keycloak moves to the `/sms` calls in [#175](https://github.com/kazetsukaimiko/freedriver-web/issues/175).

Build and test the Quarkus service with `./mvnw -pl sms verify`, and run it locally with `./mvnw -pl sms quarkus:dev`, which uses an in-memory fake sender. The compose service still builds the fail-closed stub in `sms/stub` until the compose file switches to `sms/src/main/docker/Dockerfile.compose`.

How the Quarkus service works:

- API. `SmsApi` declares the paths, validation and limits; `SmsResource` implements it and returns typed answers. Rejections are domain exceptions that exception mappers turn into the statuses above.
- Caller check. An app-wide filter requires `X-Freedriver-Sms-Secret` on every REST call and answers `401` when the secret is unset, the placeholder, missing or wrong. `/health/live` answers `200` while the process runs. `/health` and `/health/ready` answer `200` once the sender is configured, and `503` before that.
- Validation. Request bodies are checked with Jakarta Validation before anything else runs. A number that is not `+1` followed by ten digits gets `400 {"error":"bad-request"}` and counts toward no limit, with no phone list lookup, agreement check or Twilio call.
- Limits. App-wide filters apply the per-number limits and a service-wide daily cap. The cap counts only sends that reach Twilio (numbers on the phone list with an agreement on file), and every number gets the same `sent` answer whether or not the cap is reached; once it is, those sends make no Twilio call until 00:00 UTC and the service logs an error. The cap comes from `FREEDRIVER_SMS_DAILY_SEND_CAP` in server config and has no production default, so a production start without it fails.
- Phone list and agreements. `phones.json` holds the numbers that can get a code, each with its Keycloak username. `consents.jsonl` is an append-only log of sign-in agreements: the number, what it covers (sign-in codes only), the exact wording shown, where it was given (the invite page or the seeded number's first sign-in) and the time. Both live in `freedriver.sms.data-dir` (`/deployments/data` in the image). Removing a number from the list keeps its agreement records. An unlisted number gets the usual `sent` answer, no provider call, and nothing is stored.
- Seed number. At startup, `FREEDRIVER_SEED_PHONE` (sms only) puts kaze's number on the phone list as the Keycloak user `kaze` with `dashboard` when it is not there yet ([#168](https://github.com/kazetsukaimiko/freedriver-web/issues/168)). The value passes the same `+1` check as every request number, and a value that fails it stops startup. A row already on the list stays exactly as it is, so changing the setting only ever adds a number. Startup logs `Seed row added` or `Seed row already present` with the number masked to its last two digits.
- Seed agreement. The seeded number starts with no agreement record. The first send that carries the agreement wording stores it (the number, the exact wording, the time, source `SEEDED_FIRST_SIGN_IN`) and then sends the code. When the agreement cannot be stored, no code is sent, the answer stays `sent`, and the service logs an error with the number masked. That first send counts toward the daily cap like any send that reaches Twilio.
- Sender. Every sender checks for a recorded sign-in agreement right before the provider call and refuses a number without one, logging it masked. The Twilio Verify sender makes two calls only: create a verification (`POST /v2/Services/{sid}/Verifications`, Twilio generates the code) and create a verification check (`POST /v2/Services/{sid}/VerificationCheck`). It authenticates with the API key SID and secret, never sends its own code, and never stores or logs a code. Each call has a timeout (`freedriver.sms.twilio.timeout`, 5 seconds). A refused, failed or timed-out send is logged with the number masked to its last two digits, and the answer stays `sent`. There is no inbound Twilio webhook.
- Twilio settings. `TWILIO_ACCOUNT_SID`, `TWILIO_API_KEY_SID`, `TWILIO_API_KEY_SECRET` and `TWILIO_VERIFY_SERVICE_SID` are read from the environment at startup. A missing or malformed one stops startup, and the startup check makes no Twilio call. On success the service logs `Twilio credentials loaded` with no values. The base URL is `https://verify.twilio.com`; only the test profile can change it, and any other host or plain http stops startup. HTTP client request and response logging is off in every profile.

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

1. Put kaze's number in `/opt/freedriver-secrets/.env` as `FREEDRIVER_SEED_PHONE`, written as `+1` followed by ten digits with no spaces or punctuation. Only the `sms` service receives it; `sms` seeds it once into its phone list under the username `kaze` ([#168](https://github.com/kazetsukaimiko/freedriver-web/issues/168)).
2. Recreate `sms` with `docker compose --env-file /opt/freedriver-secrets/.env up -d sms`.
3. Run `./scripts/provision-keycloak-freedriver.sh` after `./scripts/provision-keycloak-sms-otp.sh`.

The script creates or updates the Keycloak user `kaze` with the `phone` attribute set to that number, direct membership in `phone-sign-in`, and the realm role `dashboard`. It stops with an error when `kaze` has another group or a role beyond `dashboard` and `default-roles-freedriver`, or when another user already has the number. It never prints the number. A second run changes nothing. With the setting empty, it leaves `kaze` as it is.
