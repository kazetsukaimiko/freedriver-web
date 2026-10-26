(function () {
  var KEY = "freedriver.login.phone";
  var form = document.getElementById("freedriver-phone-username-form");
  var input = form ? form.querySelector('input[name="username"]') : null;
  var phone = fromFragment();
  if (!isPhone(phone)) {
    try {
      phone = sessionStorage.getItem(KEY) || "";
    } catch (e) {
      phone = "";
    }
  }
  if (!isPhone(phone)) {
    return;
  }
  try {
    sessionStorage.setItem(KEY, phone);
  } catch (e) {}
  if (input) {
    input.value = phone;
  }

  function fromFragment() {
    if (location.hash.length < 2) {
      return "";
    }
    try {
      return decodeURIComponent(location.hash.substring(1));
    } catch (e) {
      return "";
    }
  }

  function isPhone(value) {
    return /^\+1[0-9]{10}$/.test(value);
  }
})();
