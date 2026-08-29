Freedriver initial assets v1
============================

Snow theme. Intricate ice-white + ice-blue snowflake. No mascot.

Color: frost white, ice-blue rim, white-hot core, semi-transparent crystal.
Backgrounds: production files are transparent PNGs. previews/ are on charcoal.

logos/
  freedriver-icon.png       snowflake mark only (also app icon / favicon source)
  freedriver-lockup.png     snowflake + FREEDRIVER wordmark
  freedriver-wordmark.png   FREEDRIVER only (ice-slash I)
  favicon.png               256px snowflake
  favicon-32.png            32px

pages/
  freedriver-404.png        fractured snowflake + 404
  freedriver-500.png        cracked snowflake + 500
  freedriver-loader.png     same as icon (spin/pulse in CSS)
  freedriver-denied.png     intact red snowflake (401/403)

previews/                   review-only, do not ship if you can avoid it

Suggested site swap (replace Lonewatt, do not keep both)
-------------------------------------------------------
site/index.html
  /assets/lonewatt/logos/lonewatt-lockup.png
    -> /assets/freedriver/logos/freedriver-lockup.png
  alt: Freedriver

site/404.html
  /assets/lonewatt/sprites/solo-404.png
    -> /assets/freedriver/pages/freedriver-404.png
  copy: drop Solo. Suggested: "That page drifted." + home link

site/500.html
  /assets/lonewatt/sprites/solo-500.png
    -> /assets/freedriver/pages/freedriver-500.png
  copy: drop Solo. Suggested: "Something froze."

site/favicon.png  and  app favicon
  -> /assets/freedriver/logos/favicon.png  (or logos/freedriver-icon.png)

app (Quinoa webui)
  lonewatt-icon / lonewatt-lockup / lonewatt-w
    -> freedriver-icon / freedriver-lockup / freedriver-icon
  solo-run loader -> freedriver-loader.png (CSS rotate or pulse)
  solo-404 / solo-500 -> freedriver-404 / freedriver-500
  Remove /assets/lonewatt/ from the repo once swapped.

These are first-pass generated marks, not final vectors.
