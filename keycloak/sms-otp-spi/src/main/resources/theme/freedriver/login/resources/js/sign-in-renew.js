// Sign-in renewal (#552). A sign-in page left open starts a fresh sign-in at its client's home
// address instead of failing with "timed out":
// - when it comes back into view after half of Keycloak's login lifespan (visibilitychange, focus,
//   pageshow);
// - when it stays in view with nothing typed, on a timer at half the lifespan;
// - when it is used after the full lifespan, once Keycloak's session has ended. Before that the
//   submit goes to Keycloak as usual. A number typed as +1 and ten digits is kept for the fresh page
//   (phone-fragment.js restores exactly that form).
// freedriver-renew.ftl loads it with data-renew-after-seconds, data-lifetime-seconds and
// data-restart-url, and only for a client with a home address.
(function (root) {
  "use strict";
  var PHONE_KEY = "freedriver.login.phone";
  var RESTORABLE = new RegExp(freedriverStoredUsPhone);

  function stale(openedAt, now, afterMs) {
    return now - openedAt >= afterMs;
  }

  function typedValue(doc) {
    var field = doc.querySelector('form input[name="username"], form input[name="phone"]');
    return field && field.value ? String(field.value).trim() : "";
  }

  function install(win, doc, script) {
    var renewAfterMs = Number(script.getAttribute("data-renew-after-seconds")) * 1000;
    var lifetimeMs = Number(script.getAttribute("data-lifetime-seconds")) * 1000;
    var url = script.getAttribute("data-restart-url");
    if (!(renewAfterMs > 0) || !(lifetimeMs > 0) || !url) {
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

    function check() {
      if (!doc.hidden && stale(openedAt, win.Date.now(), renewAfterMs)) {
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
      if (!stale(openedAt, win.Date.now(), lifetimeMs)) {
        return;
      }
      event.preventDefault();
      var value = typedValue(doc);
      if (RESTORABLE.test(value)) {
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
