package io.freedriver.app.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Invite link to the login form. The host is {@code freedriver.public-base-url} only.
 * The number is the URL fragment, never the query.
 */
public final class SignInLink {

    public static final String PATH = "/sign-in";

    static final String PHONE_KEY = "freedriver.login.phone";

    private static final Pattern PHONE = Pattern.compile("\\+1[0-9]{10}");

    /**
     * Same-origin page. It keeps a fragment number in memory and continues to {@code /login}
     * with that fragment, including when an earlier redirect dropped it.
     */
    static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="utf-8"><title>Sign in</title></head>
            <body>
            <script>
            (function () {
              var KEY = "freedriver.login.phone";
              var phone = "";
              if (location.hash.length > 1) {
                try { phone = decodeURIComponent(location.hash.substring(1)); } catch (e) { phone = ""; }
              }
              if (!/^\\+1[0-9]{10}$/.test(phone)) {
                try { phone = sessionStorage.getItem(KEY) || ""; } catch (e) { phone = ""; }
              }
              if (!/^\\+1[0-9]{10}$/.test(phone)) {
                location.replace("/login");
                return;
              }
              try { sessionStorage.setItem(KEY, phone); } catch (e) {}
              location.replace("/login#" + encodeURIComponent(phone));
            })();
            </script>
            </body>
            </html>
            """;

    private SignInLink() {}

    /** {@code publicBaseUrl} is the only host. A query or fragment on that base is dropped. */
    public static String url(String publicBaseUrl, String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("phone");
        }
        String base = baseOnly(publicBaseUrl);
        String fragment = URLEncoder.encode(phone, StandardCharsets.UTF_8).replace("+", "%20");
        return base + PATH + "#" + fragment;
    }

    private static String baseOnly(String publicBaseUrl) {
        if (publicBaseUrl == null) {
            throw new IllegalArgumentException("public base url");
        }
        String base = publicBaseUrl.trim();
        int scheme = base.indexOf("://");
        if (scheme <= 0) {
            throw new IllegalArgumentException("public base url");
        }
        int cut = base.length();
        int query = base.indexOf('?', scheme + 3);
        int hash = base.indexOf('#', scheme + 3);
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (hash >= 0) {
            cut = Math.min(cut, hash);
        }
        base = base.substring(0, cut);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.length() <= scheme + 3) {
            throw new IllegalArgumentException("public base url");
        }
        return base;
    }
}
