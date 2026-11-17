package io.freedriver.keycloak.sms;

import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in renewal (#552) as Keycloak renders it: freedriver-renew.ftl with the realm's login lifespan
 * and the client of the sign-in. A client with a home address (its baseUrl) gets the refresh and the
 * script, both pointing at that address; a client without one gets neither. The provision script gives
 * freedriver-api, and only freedriver-api, a home address.
 */
class SignInRenewTemplateTest {

    private static final Path THEME = themeDir();

    /** The client bean Keycloak puts in the template; only its home address matters here. */
    public static final class Client {
        private final String baseUrl;

        Client(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getBaseUrl() {
            return baseUrl;
        }
    }

    @Test
    void aClientWithAHomeAddressRenewsToThatAddress() throws Exception {
        String page = render(7200, new Client("https://app.freedriver.io/"));
        assertTrue(page.contains("<noscript><meta http-equiv=\"refresh\" content=\"3600;url=https://app.freedriver.io/\"></noscript>"), page);
        assertTrue(page.contains("src=\"/resources/x/login/freedriver/js/sign-in-renew.js\""), page);
        assertTrue(page.contains("data-renew-after-seconds=\"3600\""), page);
        assertTrue(page.contains("data-lifetime-seconds=\"7200\""), page);
        assertTrue(page.contains("data-restart-url=\"https://app.freedriver.io/\""), page);
    }

    @Test
    void aClientWithoutAHomeAddressGetsNoRenewal() throws Exception {
        for (Client client : new Client[] {new Client(null), new Client(""), null}) {
            String page = render(7200, client);
            assertFalse(page.contains("refresh"), page);
            assertFalse(page.contains("sign-in-renew.js"), page);
        }
    }

    @Test
    void theAddressIsTheClientsNotTheThemes() throws Exception {
        String page = render(600, new Client("https://grafana.example/"));
        assertTrue(page.contains("content=\"300;url=https://grafana.example/\""), page);
        assertTrue(page.contains("data-restart-url=\"https://grafana.example/\""), page);
        assertFalse(Files.readString(THEME.resolve("freedriver-renew.ftl")).contains("https://"),
                "freedriver-renew.ftl names no address");
    }

    @Test
    void bothSignInPagesBeforeACodeIncludeTheRenewal() throws IOException {
        for (String page : List.of("freedriver-phone-username.ftl", "freedriver-sms-phone.ftl")) {
            String form = Files.readString(THEME.resolve(page));
            assertTrue(form.contains("<#import \"freedriver-renew.ftl\" as renewal>"), page);
            assertTrue(form.contains("<@renewal.renew/>"), page);
        }
    }

    @Test
    void theProvisionScriptGivesFreedriverApiAloneAHomeAddress() throws IOException {
        String script = Files.readString(repo().resolve("scripts/provision-keycloak-freedriver.sh"));
        Matcher home = Pattern.compile("(?m)^HOME_URL='([^']+)'$").matcher(script);
        assertTrue(home.find(), "HOME_URL in the provision script");
        assertEquals("https://app.freedriver.io/", home.group(1));
        Matcher set = Pattern.compile("baseUrl=").matcher(script);
        int uses = 0;
        while (set.find()) {
            uses++;
        }
        assertEquals(2, uses, "baseUrl is set twice: when freedriver-api is created and when it is updated");
        int api = script.indexOf("-s clientId=freedriver-api");
        int update = script.indexOf("kcadm update \"clients/${CLIENT_UUID}\"");
        int next = script.indexOf("default-client-scopes", update);
        assertTrue(api > 0 && update > api && next > update, "freedriver-api's create and update blocks");
        assertTrue(script.substring(api, update).contains("-s \"baseUrl=${HOME_URL}\""), "set on create");
        assertTrue(script.substring(update, next).contains("-s \"baseUrl=${HOME_URL}\""), "set on update");
    }

    private static String render(int lifetimeSeconds, Client client) throws IOException, TemplateException {
        // Keycloak's DefaultFreeMarkerProvider: 2.3.32 rules and HTML output.
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setOutputFormat(HTMLOutputFormat.INSTANCE);
        cfg.setDirectoryForTemplateLoading(THEME.toFile());
        Map<String, Object> model = new HashMap<>();
        model.put(SignInLifetime.ATTR, lifetimeSeconds);
        model.put("url", Map.of("resourcesPath", "/resources/x/login/freedriver"));
        if (client != null) {
            model.put("client", client);
        }
        Template page = new Template("page.ftl",
                new StringReader("<#import \"freedriver-renew.ftl\" as renewal><@renewal.renew/>"), cfg);
        StringWriter out = new StringWriter();
        page.process(model, out);
        return out.toString();
    }

    private static Path repo() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("keycloak/themes/freedriver/login")) && Files.isDirectory(p.resolve("scripts"))) {
                return p;
            }
        }
        throw new AssertionError("repository root not found");
    }

    private static Path themeDir() {
        return repo().resolve("keycloak/themes/freedriver/login");
    }
}
