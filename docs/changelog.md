# Changelog API and page

The portal serves the release changelog to the read-only roles and shows it at `/changelog`.

## REST

Roles: `portal-admin`, `dashboard` or `changelog` (`@RolesAllowed` in every profile).

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/changelog` | 200 `text/plain`, the changelog file exactly as packaged |

### Status codes

| Case | Answer |
| --- | --- |
| Allowed role, changelog packaged | 200 with the file text |
| Allowed role, no changelog file in the jar | 404 |
| Signed in without an allowed role | 403 `{}` |
| No session, JavaScript request (`X-Requested-With: JavaScript`, sign-in on) | 499 |
| No session, browser opening the path (sign-in on) | 302 to sign-in |
| No session, sign-in off (dev and tests) | 401 |

`/api/changelog` is in the app-wide `authenticated` HTTP permission, next to `/api/appliances`, so a request without a session gets the same sign-in answer as the dashboard's API calls.

## Changelog file

`GET /api/changelog` reads the classpath resource **`changelog/CHANGELOG.md`** from the app jar (`io.freedriver.app.changelog.Changelog`). In the source tree that is `app/src/main/resources/changelog/CHANGELOG.md`. The release job ([#151](https://github.com/kazetsukaimiko/freedriver-web/issues/151)) generates the file and writes it to that path before the image build, so it lands in the jar. The path is outside `META-INF/resources`, so Quarkus never serves the file as a static asset.

Until that job writes the file, the endpoint answers 404 and the build stamp stays plain text.

## Web UI

- `/changelog` renders inside the portal shell with the heading "Changelog". The text from `GET /api/changelog` sits in a card, exactly as served, with line breaks kept, in the portal font at body size, wrapping long lines.
- A 404 shows "No changelog yet." in the card. A 403 shows the Access denied page. A 499 (or a 401 while sign-in is off) sends the page to `/login`, the same as the dashboard.
- The build stamp probes `GET /api/changelog` once. On 200 the stamp text links to `/changelog` in the same tab, underlined on hover. On any other answer it stays plain text, and the probe never navigates.
