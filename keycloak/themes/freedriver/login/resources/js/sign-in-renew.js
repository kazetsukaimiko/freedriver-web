// Sign-in renewal (#552). A sign-in page that has been open longer than half of Keycloak's login
// lifespan starts a fresh sign-in at the dashboard instead of failing with "timed out":
// - when it comes back into view (visibilitychange, focus, pageshow);
// - when it stays in view with nothing typed, on a timer;
// - when it is used: the typed number is kept for the fresh page (phone-fragment.js reads it).
// freedriver-renew.ftl loads it with data-renew-after-seconds and data-restart-url.
(function (root) {
  "use strict";
  var PHONE_KEY = "freedriver.login.phone";

  function stale(openedAt, now, renewAfterMs) {
    return now - openedAt >= renewAfterMs;
  }

  function typedValue(doc) {
    var field = doc.querySelector('form input[name="username"], form input[name="phone"]');
    return field && field.value ? String(field.value).trim() : "";
  }

  function install(win, doc, script) {
    var renewAfterMs = Number(script.getAttribute("data-renew-after-seconds")) * 1000;
    var url = script.getAttribute("data-restart-url");
    if (!(renewAfterMs > 0) || !url) {
      return null;
    }
    var openedAt = win.Date.now();
    var renewing = false;

    function renew() {
      if (renewing) {
        return;
      }
      renewing = true;
      win.location.replace(url);
    }

    function isStale() {
      return stale(openedAt, win.Date.now(), renewAfterMs);
    }

    function check() {
      if (!doc.hidden && isStale()) {
        renew();
      }
    }

    doc.addEventListener("visibilitychange", check);
    win.addEventListener("focus", check);
    win.addEventListener("pageshow", check);
    win.setTimeout(function () {
      if (!typedValue(doc)) {
        renew();
      }
    }, renewAfterMs);
    doc.addEventListener("submit", function (event) {
      if (!isStale()) {
        return;
      }
      event.preventDefault();
      var value = typedValue(doc);
      if (value) {
        try {
          win.sessionStorage.setItem(PHONE_KEY, value);
        } catch (e) {}
      }
      renew();
    }, true);
    return { check: check, renew: renew };
  }

  root.freedriverSignInRenew = { install: install, stale: stale };
  var current = root.document && root.document.currentScript;
  if (current && current.getAttribute("data-renew-after-seconds")) {
    install(root, root.document, current);
  }
})(window);
